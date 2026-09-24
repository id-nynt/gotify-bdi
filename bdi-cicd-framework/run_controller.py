"""Validate persistent project artifacts and start a campaign without generation."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import uuid
import hashlib
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "parser"))
from model_transform import ModelError  # noqa: E402
from project_artifacts import validate, paths  # noqa: E402
import shutil
from workflow_model import runtime_settings


def run_with_console_log(command, *, cwd, env, log_path: Path) -> int:
    """Keep live console output and durable evidence for GUI and terminal runs."""
    with log_path.open("w", encoding="utf-8") as log:
        with subprocess.Popen(command, cwd=cwd, env=env, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                              errors="replace") as process:
            for line in process.stdout:
                log.write(line)
                log.flush()
                print(line, end="", flush=True)
            return process.wait()


def load_mapping(path: Path) -> dict:
    value = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ModelError(f"{path}: top level must be a mapping")
    return value


def known_good_sha(receipt: Path, project: Path, model, repository: str) -> str:
    data = json.loads(receipt.read_text(encoding="utf-8"))
    config = project if isinstance(project, dict) else load_mapping(project)
    if data.get("mode") != "github" or data.get("outcome") != "achieved" or data.get("project") != config["project"] or data.get("repository") != repository:
        raise ModelError("Known-good receipt must be an achieved live campaign for this project/repository")
    sha = data.get("release_sha", "")
    import re
    if not re.fullmatch(r"[0-9a-fA-F]{40}", sha):
        raise ModelError("Known-good receipt requires an immutable 40-character commit SHA")
    for source, _ in model.recovery:
        if source in model.required_entities:
            verified = data.get("verified_releases", {}).get(source, {})
            if verified.get("release_sha") != sha or not verified.get("github_run_id") or verified.get("environment") != config["controller"]["environments"].get(source):
                raise ModelError(f"Receipt does not verify recovery source {source} in the same environment")
    return sha


def git_sha(repository_root: Path) -> str:
    result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repository_root,
                            text=True, capture_output=True, check=True)
    return result.stdout.strip()


def validate_live_environment(environment):
    import re
    repository = environment.get('GITHUB_REPOSITORY', '')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ModelError('Set GITHUB_REPOSITORY=owner/repository in this PowerShell window before launching')
    token = environment.get('GITHUB_TOKEN', '')
    if not token or any(ord(c) < 33 or ord(c) > 126 for c in token):
        raise ModelError('GITHUB_TOKEN is missing or contains whitespace/control characters. Re-enter the token in this window; its value is never printed')


def conventional_policy(document):
    """Reject unsupported contracts rather than silently comparing a different policy."""
    w, g = document['workflow'], document['goals']
    normal = ['build', 'test', 'security', 'staging', 'production']
    expected_edges = [{'from': a, 'to': b} for a, b in zip(normal, normal[1:])]
    if (w['entities(E)'] != normal + ['rollback'] or w['dependencies(D)'] != expected_edges or
        w['recovery(R)'] != [{'from': 'production', 'to': 'rollback'}] or
        set(g['achieve(A)']) != {'production.status == success', 'staging.status == success'} or
        g.get('duration_unit') != 'milliseconds' or
        document['observation_schema']['before'] != {'production': 'staging'} or
        set(document['observation_schema']['after']) != {'staging', 'production', 'rollback'}):
        raise ModelError('Conventional baseline supports only the payment success-goal topology/observation contract')
    import re
    constraints = g.get('maintain(M)', [])
    duration = [re.fullmatch(r'production.duration <= ([0-9]+)', rule) for rule in constraints]
    duration = [m for m in duration if m]
    expected_avoid = [{'condition': 'production.status == success', 'when': f'{e}.status != success'} for e in ('test', 'staging')]
    if len(constraints) != 2 or 'production.health == healthy' not in constraints or len(duration) != 1 or g.get('avoid(V)') != expected_avoid:
        raise ModelError('Conventional baseline requires the payment maintenance/avoidance rules')
    return {'execution': document['execution'], 'max_production_ms': int(duration[0][1]),
            'thresholds': document['bindings']['thresholds'],
            'recovery_triggers': document['recovery_policy']['rollback']['run_after'],
            **({'candidate_repair':document['candidate_repair']} if 'candidate_repair' in document else {}),
            **({'rollback_reconsideration':document['rollback_reconsideration']} if 'rollback_reconsideration' in document else {})}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-dir", type=Path, default=ROOT, help="persistent generated project directory")
    parser.add_argument("--validate-only", action="store_true", help="check consistency without starting a campaign")
    parser.add_argument("--scenario", choices=["healthy", "staging_failure", "transient_test_failure", "exhausted_test_failure",
                                                "telemetry_block", "telemetry_unknown", "telemetry_delayed", "telemetry_transient", "production_transient", "telemetry_flapping", "observation_deadline", "deterministic_test_failure", "dispatch_rejected", "production_retry",
                                                "production_failure", "production_unhealthy", "production_unknown",
                                                "rollback_failure", "rollback_unknown", "rollback_unhealthy", "execution_uncertain", "reconciled_success", "reconciled_failure", "candidate_stopped", "candidate_restart_fails", "candidate_repair_unknown", "rollback_reconsideration"])
    parser.add_argument("--known-good", type=Path, help="achieved live campaign result verifying the baseline release")
    parser.add_argument("--baseline", action="store_true", help="explicit first baseline run without prior recovery release")
    parser.add_argument("--campaign-id", help="fresh identifier for external scenario correlation; does not select a scenario")
    parser.add_argument("--confirm-compatible-rollback", action="store_true", help="confirm source rollback is compatible with retained database schema/data")
    parser.add_argument("--artifacts-dir", type=Path,
                        help="new directory for this campaign's provenance, snapshots, journal and result")
    parser.add_argument("--pause-after", help="comma-separated successful entities after which to pause before returning results")
    parser.add_argument("--pause-ms", type=int, default=0)
    parser.add_argument("--reconcile-only", action="store_true", help="read remote status of durable pending execution; never dispatch or resume a campaign")
    parser.add_argument("--rejected-dispatch-evidence", type=Path,
                        help="with --reconcile-only: original campaign directory proving an old HTTP rejection or invalid Authorization header")
    parser.add_argument("--gui", action="store_true", help="open Jason MAS Console and keep the final agent mind available until closed")
    parser.add_argument('--mechanism', choices=['bdi', 'conventional'], default='bdi')
    args = parser.parse_args()
    if args.mechanism == 'conventional' and (args.gui or args.reconcile_only):
        raise ModelError('Conventional mode has no MAS GUI; use the shared default --reconcile-only command for interrupted execution')

    if args.rejected_dispatch_evidence and (not args.reconcile_only or args.validate_only):
        raise ModelError("--rejected-dispatch-evidence requires --reconcile-only without --validate-only")

    if args.reconcile_only and (args.scenario or args.known_good or args.baseline):
        raise ModelError("--reconcile-only cannot be combined with scenario or release options")
    document, model, generation, inputs = validate(args.project_dir)
    project = runtime_settings(document)
    baseline_policy = conventional_policy(document) if args.mechanism == 'conventional' else None
    if baseline_policy and document.get('candidate_repair') and not args.scenario and not args.validate_only:
        raise ModelError('Candidate repair comparison requires native ci-cd-conventional workflows; the legacy Java conventional harness is simulation-only for this contract')
    conventional_scenarios = {'healthy', 'transient_test_failure', 'exhausted_test_failure', 'deterministic_test_failure',
        'staging_failure', 'production_failure', 'dispatch_rejected', 'execution_uncertain', 'reconciled_success',
        'reconciled_failure', 'production_retry', 'production_unhealthy', 'telemetry_block', 'telemetry_transient', 'production_transient'}
    if baseline_policy and args.scenario and args.scenario not in conventional_scenarios:
        raise ModelError('This simulated scenario is not implemented by the conventional adapter')
    if args.validate_only:
        print(f"Project artifacts are consistent: {args.project_dir.resolve()}")
        return 0

    if not args.scenario:
        validate_live_environment(os.environ)

    baseline_sha = ""
    recovery_required = any(source in model.required_entities for source, _ in model.recovery)
    if args.baseline and args.known_good:
        raise ModelError("Choose --baseline or --known-good, not both")
    if args.scenario and not args.baseline:
        baseline_sha = "1" * 40  # Explicitly simulated; never accepted as a live receipt.
    elif args.known_good:
        if not args.confirm_compatible_rollback:
            raise ModelError("Source rollback requires --confirm-compatible-rollback; no database rollback is performed")
        baseline_sha = known_good_sha(args.known_good, project, model, os.environ.get("GITHUB_REPOSITORY", ""))
    elif recovery_required and not args.baseline and not args.reconcile_only:
        raise ModelError("Provide --known-good verified-result.json, or --baseline for the initial known-good deployment")

    campaign = args.campaign_id or "campaign-" + uuid.uuid4().hex
    if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,100}', campaign):
        raise ModelError('Campaign identifier must be a safe identifier')
    artifacts = args.artifacts_dir.resolve() if args.artifacts_dir else ROOT / "runs" / campaign
    try:
        artifacts.mkdir(parents=True, exist_ok=False)
    except FileExistsError as error:
        raise ModelError(f"Campaign directory already exists: {artifacts}. Preserve its evidence; "
                         "choose a fresh --artifacts-dir or omit it for an automatic unique directory.") from error
    persistent_workflow, persistent_agent, generation_manifest = paths(args.project_dir)
    workflow = artifacts / "03_workflow_model.yaml"
    agent = artifacts / "controller_agent.asl"
    mas = artifacts / "controller.mas2j"
    manifest = artifacts / "generation-manifest.json"
    # Immutable archival copies of the validated project revision, never regenerated.
    for source, target in [(persistent_workflow, workflow), (persistent_agent, agent),
                           (generation_manifest, artifacts / "project-generation-manifest.json"),
                           (inputs['pipeline'], artifacts / "01_pipeline.input.yaml"),
                           (inputs['goal'], artifacts / "02_goal.input.yaml"),
                           (inputs['policy'], artifacts / "controller_policy.input.yaml"),
                           (inputs['bindings'], artifacts / "runtime_bindings.input.yaml"),
                           (ROOT / "bdi/controller.mas2j", mas)]:
        shutil.copyfile(source, target)
    # Detect concurrent edits during snapshot creation, before Jason/Java can dispatch.
    if validate(args.project_dir)[2] != generation:
        raise ModelError("Project revision changed while snapshotting; restart after generation finishes")
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    for source, target in [(persistent_workflow, workflow), (persistent_agent, agent),
                           (generation_manifest, artifacts / "project-generation-manifest.json"),
                           (inputs['pipeline'], artifacts / "01_pipeline.input.yaml"),
                           (inputs['goal'], artifacts / "02_goal.input.yaml"),
                           (inputs['policy'], artifacts / "controller_policy.input.yaml"),
                           (inputs['bindings'], artifacts / "runtime_bindings.input.yaml")]:
        if digest(source) != digest(target):
            raise ModelError("Project artifacts changed while snapshotting; restart after generation finishes")
    record = {"schema_version": 1, "campaign_id": campaign, "inputs": generation['inputs'],
              "persistent_artifacts": {"workflow": str(persistent_workflow), "agent": str(persistent_agent),
                                       "manifest": str(generation_manifest), "manifest_sha256": digest(generation_manifest)},
              "workflow_sha256": digest(workflow), "generated_agent_sha256": digest(agent),
              "mas_sha256": digest(mas), "required_entities": list(model.required_entities),
              "achievements": [item.entity for item in model.achievements]}
    source_files = [ROOT / "run_controller.py", ROOT / "project_artifacts.py", ROOT / "generate_project.py",
                    ROOT / "parser/model_transform.py", ROOT / "parser/workflow_model.py",
                    ROOT.parent / ".github/workflows/entity-execution.yml", *sorted((ROOT / "bdi/harness").glob("*.java")),
                    *sorted((ROOT / "monitoring").rglob("*.java")), ROOT / "bdi/build.gradle"]
    record["source_files_sha256"] = {str(p.relative_to(ROOT.parent)): digest(p) for p in source_files}
    manifest.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(f"Starting {campaign} using existing project artifacts in {args.project_dir.resolve()}", flush=True)

    repository_root = ROOT.parent
    environment = os.environ.copy()
    environment['EXPERIMENT_MECHANISM'] = args.mechanism
    environment['EXPERIMENT_EVENTS_FILE'] = str(artifacts / 'experiment-events.jsonl')
    if baseline_policy:
        policy_path = artifacts / 'conventional-policy.json'
        policy_path.write_text(json.dumps(baseline_policy, indent=2) + '\n', encoding='utf-8')
        environment['BDI_CONVENTIONAL_POLICY'] = str(policy_path)
    environment["BDI_RECONCILE_ONLY"] = str(args.reconcile_only).lower()
    environment.pop("BDI_REJECTED_DISPATCH_EVIDENCE", None)
    if args.rejected_dispatch_evidence:
        environment["BDI_REJECTED_DISPATCH_EVIDENCE"] = str(args.rejected_dispatch_evidence.resolve())
    environment["BDI_GUI"] = str(args.gui).lower()
    environment.pop("BDI_SCENARIO", None)
    environment["BDI_KNOWN_GOOD_SHA"] = baseline_sha
    environment["BDI_GOALS"] = json.dumps([{"entity": item.entity, "status": item.value} for item in model.achievements])
    environment["BDI_HEALTH_GOALS"] = json.dumps([item.entity for item in model.maintenance if item.property == "health"])
    environment["BDI_MANIFEST_FILE"] = str(manifest)
    environment["BDI_PROJECT_FILE"] = str(workflow)
    environment["BDI_MAS_FILE"] = str(mas)
    environment["BDI_RUN_DIR"] = str(artifacts)
    environment["BDI_LOG_CONFIG"] = str(ROOT / "bdi" / ("logging-gui.properties" if args.gui else "logging.properties"))
    environment["BDI_CAMPAIGN_ID"] = campaign
    environment.setdefault("BDI_RELEASE_SHA", git_sha(repository_root))
    record = json.loads(manifest.read_text(encoding="utf-8"))
    if baseline_policy:
        record['conventional_policy_sha256'] = hashlib.sha256(policy_path.read_bytes()).hexdigest()
    record.update({"mechanism": args.mechanism, "release_sha": environment["BDI_RELEASE_SHA"], "known_good_sha": baseline_sha,
                   "controller_source_sha": git_sha(repository_root),
                   "workflow_ref": environment.get("BDI_WORKFLOW_REF", "main"),
                   "generated_agent_sha256": hashlib.sha256(agent.read_bytes()).hexdigest(),
                   "generic_policy_sha256": hashlib.sha256((ROOT / "generator/controller_generic.asl").read_bytes()).hexdigest()})
    manifest.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    environment["BDI_JOURNAL_FILE"] = str(artifacts / "controller-journal.jsonl")
    environment["BDI_RESULT_FILE"] = str(artifacts / "controller-result.json")
    # One repository-wide lock prevents concurrent campaigns controlling the same targets.
    environment["BDI_LOCK_FILE"] = str(Path(subprocess.check_output(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], cwd=ROOT.parent, text=True).strip()) / "bdi-controller.lock")
    environment["BDI_EXECUTION_STATE_FILE"] = str(Path(environment["BDI_LOCK_FILE"]).with_name("bdi-execution-pending.json"))
    if args.scenario:
        environment["BDI_SCENARIO"] = args.scenario
    if args.pause_after:
        environment["BDI_PAUSE_AFTER_ENTITY"] = args.pause_after
        environment["BDI_PAUSE_MILLISECONDS"] = str(args.pause_ms)
    wrapper = ROOT / "bdi" / ("gradlew.bat" if os.name == "nt" else "gradlew")
    # The repository wrapper may be checked out without an executable bit.
    command = ([str(wrapper)] if os.name == "nt" else ["bash", str(wrapper)]) + ["--no-daemon", "runConventional" if args.mechanism == "conventional" else "runController"]
    returncode = run_with_console_log(command, cwd=ROOT / "bdi", env=environment,
                                      log_path=artifacts / "controller-console.log")
    result_path = Path(environment["BDI_RESULT_FILE"])
    if not result_path.exists():
        return returncode or 2
    if args.reconcile_only:
        return returncode
    outcome = json.loads(result_path.read_text(encoding="utf-8")).get("outcome", "unknown")
    return {"achieved": 0, "stopped": 1, "unknown": 2}.get(outcome, 2)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ModelError, OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"controller startup failed: {error}", file=sys.stderr)
        raise SystemExit(2)
