"""Deterministic transformation of the supported pipeline/goal YAML subset.

The parser is deliberately strict. It validates the source configuration before
emitting the workflow model and the project-specific AgentSpeak beliefs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml


ATOM = re.compile(r"^[a-z_][a-z0-9_]*$")
COMPARISON = re.compile(r"^([a-z_][a-z0-9_]*)\.([a-z_][a-z0-9_]*)\s*(==|!=|<=|>=|<|>)\s*([a-z_][a-z0-9_]*|-?[0-9]+(?:\.[0-9]+)?)$")
RECOVERY_IF = re.compile(r"needs\.([a-z_][a-z0-9_]*)\.result\s*==\s*['\"]failure['\"]")
SUPPORTED_STATUS = ["success", "failure", "cancelled", "skipped", "timeout"]
SUPPORTED_PROPERTIES = {"status", "duration", "health", "latency", "error_rate"}


class ModelError(ValueError):
    """Raised for unsupported, malformed, or ambiguous source configuration."""


@dataclass(frozen=True)
class Achievement:
    entity: str
    property: str
    operator: str
    value: str


@dataclass(frozen=True)
class Maintenance:
    entity: str
    property: str
    operator: str
    value: int | float | str


@dataclass(frozen=True)
class Avoidance:
    entity: str
    required: str


@dataclass(frozen=True)
class Model:
    name: str
    entities: tuple[str, ...]
    dependencies: tuple[tuple[str, str], ...]
    recovery: tuple[tuple[str, str], ...]
    achievements: tuple[Achievement, ...]
    maintenance: tuple[Maintenance, ...]
    avoidance: tuple[Avoidance, ...]
    duration_unit: str
    max_retries: int
    promotion_gate: tuple[str, str] | None = None
    observations: tuple[tuple[str, str], ...] = ()
    recovery_triggers: tuple[tuple[str, str, str], ...] = ()
    observe_after: tuple[str, ...] = ()

    @property
    def final_entity(self) -> str:
        sources = {source for source, _ in self.dependencies}
        targets = {target for _, target in self.dependencies}
        normal = set(self.entities) - {target for _, target in self.recovery}
        sinks = sorted(normal - sources)
        if len(sinks) != 1:
            raise ModelError(f"workflow must have exactly one final normal entity; found {sinks}")
        if targets and sinks[0] not in targets and len(normal) > 1:
            raise ModelError(f"final entity {sinks[0]} is disconnected from dependencies")
        return sinks[0]

    @property
    def required_entities(self) -> tuple[str, ...]:
        """Entities needed to satisfy the declared achievements and safety prerequisites."""
        required = {item.entity for item in self.achievements}
        required.update(item.entity for item in self.maintenance)
        changed = True
        while changed:
            changed = False
            for source, target in self.dependencies:
                if target in required and source not in required:
                    required.add(source)
                    changed = True
            for item in self.avoidance:
                if item.entity in required and item.required not in required:
                    required.add(item.required)
                    changed = True
        return tuple(entity for entity in self.entities if entity in required)


def _load(path: Path) -> dict[str, Any]:
    if isinstance(path, dict):
        return path
    try:
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        raise ModelError(f"{path}: invalid YAML: {exc}") from exc
    if not isinstance(value, dict):
        raise ModelError(f"{path}: top level must be a mapping")
    return value


def _atom(value: Any, context: str) -> str:
    if not isinstance(value, str) or not ATOM.fullmatch(value):
        raise ModelError(f"{context}: expected lowercase atom, got {value!r}")
    return value


def _list(value: Any, context: str) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return value
    raise ModelError(f"{context}: expected scalar or list")


def _comparison(value: Any, context: str) -> tuple[str, str, str, str]:
    if not isinstance(value, str):
        raise ModelError(f"{context}: expected comparison string")
    match = COMPARISON.fullmatch(value.strip())
    if not match:
        raise ModelError(f"{context}: malformed comparison {value!r}")
    entity, prop, operator, rhs = match.groups()
    return entity, prop, operator, rhs


def parse_pipeline(path: Path) -> tuple[str, tuple[str, ...], tuple[tuple[str, str], ...], tuple[tuple[str, str], ...], int, tuple[tuple[str, str], ...]]:
    data = _load(path)
    allowed_root = {"name", "project_file", "jobs", "execution", "on", True}
    unsupported_root = set(data) - allowed_root
    if unsupported_root:
        raise ModelError(f"{path}: unsupported top-level keys {sorted(map(str, unsupported_root))}")
    jobs = data.get("jobs")
    if not isinstance(jobs, dict) or not jobs:
        raise ModelError(f"{path}: jobs must be a non-empty mapping")
    entities = tuple(_atom(name, "pipeline job") for name in jobs)
    entity_set = set(entities)
    dependencies: list[tuple[str, str]] = []
    recovery: list[tuple[str, str]] = []
    observations: list[tuple[str, str]] = []
    recovery_jobs: set[str] = set()
    for entity, config in jobs.items():
        if not isinstance(config, dict):
            raise ModelError(f"job {entity}: expected mapping")
        unsupported_job = set(config) - {"needs", "if", "runs-on", "steps", "timeout-minutes", "observe_before", "recover_from", "recover_on", "observe_after"}
        if unsupported_job:
            raise ModelError(f"job {entity}: unsupported keys {sorted(map(str, unsupported_job))}")
        needs = [_atom(item, f"job {entity}.needs") for item in _list(config.get("needs"), f"job {entity}.needs")]
        unknown = sorted(set(needs) - entity_set)
        if unknown:
            raise ModelError(f"job {entity}: unknown dependency {unknown[0]}")
        condition = config.get("if")
        matches = RECOVERY_IF.findall(condition) if isinstance(condition, str) else []
        if condition is not None and not isinstance(condition, str):
            raise ModelError(f"job {entity}.if: expected string")
        if "recover_from" in config:
            source = _atom(config["recover_from"], f"job {entity}.recover_from")
            if source not in entity_set or source == entity or needs or condition is not None:
                raise ModelError(f"job {entity}: recovery requires another known entity and no needs/if")
            triggers = config.get("recover_on")
            if not isinstance(triggers, list) or not triggers or set(triggers) - {"failure", "telemetry_block", "telemetry_unknown", "maintenance_violation"}:
                raise ModelError(f"job {entity}: unsupported recover_on triggers")
            if config.get("observe_after") is not True:
                raise ModelError(f"job {entity}: recovery must observe_after: true")
            recovery_jobs.add(entity)
            recovery.append((source, entity))
        elif matches:
            if len(matches) != 1 or len(needs) != 1 or needs[0] != matches[0]:
                raise ModelError(f"job {entity}: recovery condition must identify its single needs target")
            recovery_jobs.add(entity)
            recovery.append((matches[0], entity))
        elif condition is not None and "needs." in condition:
            raise ModelError(f"job {entity}.if: unsupported or ambiguous recovery condition")
        else:
            dependencies.extend((need, entity) for need in needs)
        observed = config.get("observe_before")
        if observed is not None:
            source = _atom(observed, f"job {entity}.observe_before")
            if source not in entity_set or source == entity:
                raise ModelError(f"job {entity}.observe_before: expected another known entity")
            if source not in needs:
                raise ModelError(f"job {entity}.observe_before must be one of its direct dependencies")
            observations.append((entity, source))
    if len(set(recovery)) != len(recovery):
        raise ModelError("duplicate recovery relationship")
    if len({source for source, _ in recovery}) != len(recovery):
        raise ModelError("only one recovery entity per source is supported")
    if any(source in recovery_jobs for source, _ in recovery) or any(source in recovery_jobs or target in recovery_jobs for source, target in dependencies):
        raise ModelError("recovery must be a conditional leaf outside normal dependencies")
    _check_acyclic(entities, dependencies)
    execution = data.get("execution", {})
    if not isinstance(execution, dict):
        raise ModelError("pipeline.execution: expected mapping")
    retries = execution.get("max_retries")
    if not isinstance(retries, int) or isinstance(retries, bool) or retries < 0:
        raise ModelError("pipeline.execution.max_retries: expected non-negative integer")
    return (str(data.get("name", "CI/CD Pipeline")), entities, tuple(dependencies),
            tuple(recovery), retries, tuple(observations))


def parse_project_pipeline(path: Path, project_path: Path) -> tuple[str, tuple[str, ...], tuple[tuple[str, str], ...], tuple[tuple[str, str], ...], int]:
    """Map real GitHub job IDs to stable BDI entities without copying a workflow."""
    pipeline = _load(path)
    project = _load(project_path)
    jobs = pipeline.get("jobs")
    aliases = project.get("jobs")
    if not isinstance(jobs, dict) or not isinstance(aliases, dict) or not aliases:
        raise ModelError("project workflow and job mapping must be non-empty mappings")
    entities = tuple(_atom(role, "project job role") for role in aliases)
    if len(set(aliases.values())) != len(aliases):
        raise ModelError("project job IDs must be unique")
    reverse = {job_id: role for role, job_id in aliases.items()}
    infrastructure_jobs = _list(project.get("infrastructure_jobs"), "project.infrastructure_jobs")
    if any(not isinstance(job_id, str) or not job_id for job_id in infrastructure_jobs):
        raise ModelError("project.infrastructure_jobs must contain non-empty job IDs")
    if len(set(infrastructure_jobs)) != len(infrastructure_jobs):
        raise ModelError("project.infrastructure_jobs must be unique")
    for job_id in infrastructure_jobs:
        if job_id not in jobs or job_id in reverse:
            raise ModelError(f"infrastructure job {job_id!r} must be an unmapped workflow job")
    for role, job_id in aliases.items():
        if not isinstance(job_id, str) or job_id not in jobs:
            raise ModelError(f"project role {role}: workflow job {job_id!r} is missing")
    def mapped_dependencies(job_id: str, trail: frozenset[str] = frozenset()) -> set[str]:
        if job_id in trail:
            raise ModelError(f"cyclic dependency through infrastructure job {job_id!r}")
        if job_id in reverse:
            return {reverse[job_id]}
        if job_id not in infrastructure_jobs:
            raise ModelError(f"dependency {job_id!r} has no project role")
        config = jobs[job_id]
        if not isinstance(config, dict):
            raise ModelError(f"workflow job {job_id}: expected mapping")
        found: set[str] = set()
        for needed in _list(config.get("needs"), f"job {job_id}.needs"):
            found.update(mapped_dependencies(needed, trail | {job_id}))
        if not found:
            raise ModelError(f"infrastructure job {job_id!r} has no mapped predecessor")
        return found

    dependencies: list[tuple[str, str]] = []
    for role, job_id in aliases.items():
        config = jobs[job_id]
        if not isinstance(config, dict):
            raise ModelError(f"workflow job {job_id}: expected mapping")
        for needed_job in _list(config.get("needs"), f"job {job_id}.needs"):
            for predecessor in sorted(mapped_dependencies(needed_job)):
                dependencies.append((predecessor, role))
    _check_acyclic(entities, dependencies)
    retries = project.get("max_retries", 0)
    if not isinstance(retries, int) or isinstance(retries, bool) or retries < 0:
        raise ModelError("project.max_retries: expected non-negative integer")
    return str(pipeline.get("name", "CI/CD Pipeline")), entities, tuple(dependencies), (), retries


def _check_acyclic(entities: tuple[str, ...], dependencies: list[tuple[str, str]]) -> None:
    graph = {entity: [] for entity in entities}
    for source, target in dependencies:
        graph[source].append(target)
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str) -> None:
        if node in visiting:
            raise ModelError(f"cyclic dependency detected at {node}")
        if node in visited:
            return
        visiting.add(node)
        for child in graph[node]:
            visit(child)
        visiting.remove(node)
        visited.add(node)

    for entity in entities:
        visit(entity)


def parse_goals(path: Path, entities: tuple[str, ...], recovery: tuple[tuple[str, str], ...]) -> tuple[tuple[Achievement, ...], tuple[Maintenance, ...], tuple[tuple[Avoidance, ...], str]]:
    data = _load(path).get("goal")
    if not isinstance(data, dict):
        raise ModelError(f"{path}: goal must be a mapping")
    unsupported_goal = set(data) - {"achieve(A)", "maintain(M)", "avoid(V)", "duration_unit"}
    if unsupported_goal:
        raise ModelError(f"{path}: unsupported goal keys {sorted(map(str, unsupported_goal))}")
    entity_set = set(entities)
    achievements: list[Achievement] = []
    for raw in data.get("achieve(A)", []):
        entity, prop, operator, value = _comparison(raw, "goal.achieve(A)")
        _validate_ref(entity, prop, value, entity_set, "achievement")
        if operator != "==" or prop != "status" or value not in {"success", "failure"}:
            raise ModelError("achievement supports entity.status == success or failure")
        achievements.append(Achievement(entity, prop, operator, value))
    maintenance: list[Maintenance] = []
    for raw in data.get("maintain(M)", []):
        entity, prop, operator, value = _comparison(raw, "goal.maintain(M)")
        _validate_ref(entity, prop, value, entity_set, "maintenance")
        if (prop, operator, value) == ("health", "==", "healthy"):
            maintenance.append(Maintenance(entity, prop, operator, value))
        elif prop == "duration" and operator == "<=" and re.fullmatch(r"[0-9]+", value):
            maintenance.append(Maintenance(entity, prop, operator, int(value)))
        else:
            raise ModelError("maintenance supports duration <= non-negative integer or health == healthy")
    duration_unit = data.get("duration_unit", "milliseconds")
    if duration_unit != "milliseconds":
        raise ModelError("goal.duration_unit: only milliseconds is supported")
    avoidance: list[Avoidance] = []
    for index, raw in enumerate(data.get("avoid(V)", [])):
        if not isinstance(raw, dict) or set(raw) != {"condition", "when"}:
            raise ModelError(f"goal.avoid(V)[{index}]: expected condition and when mappings")
        left = _comparison(raw["condition"], f"goal.avoid(V)[{index}].condition")
        right = _comparison(raw["when"], f"goal.avoid(V)[{index}].when")
        le, lp, lo, lv = left
        re_, rp, ro, rv = right
        _validate_ref(le, lp, lv, entity_set, "avoidance condition")
        _validate_ref(re_, rp, rv, entity_set, "avoidance when")
        if (lp, lo, lv) != ("status", "==", "success") or (rp, ro, rv) != ("status", "!=", "success"):
            raise ModelError("avoidance supports success conditioned on a predecessor not succeeding")
        avoidance.append(Avoidance(le, re_))
    for target, recovery_entity in recovery:
        if target not in entity_set or recovery_entity not in entity_set:
            raise ModelError("recovery references unknown entity")
    return tuple(achievements), tuple(maintenance), (tuple(avoidance), duration_unit)


def _validate_ref(entity: str, prop: str, value: str, entities: set[str], context: str) -> None:
    if entity not in entities:
        raise ModelError(f"{context}: unknown entity {entity}")
    if prop not in SUPPORTED_PROPERTIES:
        raise ModelError(f"{context}: unsupported observable property {prop}")
    if prop == "status" and value not in SUPPORTED_STATUS and value != "success":
        raise ModelError(f"{context}: unsupported status value {value}")


def parse_model(pipeline_path: Path, goal_path: Path, project_path: Path | None = None) -> Model:
    gate = None
    if project_path is None:
        name, entities, dependencies, recovery, retries, observations = parse_pipeline(pipeline_path)
    else:
        name, entities, dependencies, recovery, retries = parse_project_pipeline(pipeline_path, project_path)
        observations = ()
        configured = _load(project_path).get("promotion_gate")
        if configured is not None:
            if not isinstance(configured, dict) or set(configured) != {"before", "observe"}:
                raise ModelError("promotion_gate requires before and observe roles")
            before = _atom(configured["before"], "promotion_gate.before")
            observe = _atom(configured["observe"], "promotion_gate.observe")
            if before not in entities or observe not in entities or (observe, before) not in dependencies:
                raise ModelError("promotion_gate must observe a direct predecessor of its target")
            gate = (before, observe)
    achievements, maintenance, (avoidance, duration_unit) = parse_goals(goal_path, entities, recovery)
    if not achievements:
        raise ModelError("goal.achieve(A): at least one achievement is required")
    recovery_entities = {target for _, target in recovery}
    if any(item.entity in recovery_entities for item in (*achievements, *maintenance)):
        raise ModelError("recovery entities cannot be normal goal targets")
    triggers = ()
    observe_after = ()
    if project_path is None:
        jobs = _load(pipeline_path)["jobs"]
        triggers = tuple((source, trigger, target) for source, target in recovery
                         for trigger in jobs[target].get("recover_on", ["failure"]))
        observe_after = tuple(entity for entity, config in jobs.items() if config.get("observe_after") is True)
    return Model(name, entities, dependencies, recovery, achievements, maintenance, avoidance,
                 duration_unit, retries, gate, observations, triggers, observe_after)


def workflow_yaml(model: Model) -> str:
    observables: dict[str, Any] = {"status": {"values": SUPPORTED_STATUS}, "duration": {"value": "time"}}
    observables["health"] = {"values": ["healthy", "unhealthy", "unknown"], "source": "project manifest readiness and run-correlated Prometheus queries"}
    for achievement in model.achievements:
        if achievement.property == "status":
            observables.setdefault("status", {"values": SUPPORTED_STATUS})
    document: dict[str, Any] = {
        "workflow": {
            "name": model.name,
            "entities(E)": list(model.entities),
            "dependencies(D)": [{"from": source, "to": target} for source, target in model.dependencies],
            "observable_properties(O)": observables,
            "recovery(R)": [{"from": source, "to": target} for source, target in model.recovery],
            "observe_before": [{"entity": target, "source": source} for target, source in model.observations],
            "observe_after": list(model.observe_after),
        },
        "execution": {
            "max_retries": model.max_retries,
            "observation_schema": {
                "status": SUPPORTED_STATUS,
                "duration_unit": model.duration_unit,
                "required_for": list(model.required_entities),
                "attempt_id_required": True,
            },
        },
        "recovery_policy": {
            recovery_entity: {
                "triggers": [reason for src, reason, target in model.recovery_triggers if target == recovery_entity],
                "retryable": False,
                "terminal_on_success": "stopped_with_service_restored",
                "terminal_on_failure": "failed",
            }
            for source, recovery_entity in model.recovery
        },
    }
    return "# Generated from 01_pipeline.yaml and 02_goal.yaml\n" + yaml.safe_dump(document, sort_keys=False, default_flow_style=False)


def project_beliefs(model: Model) -> str:
    lines = ["// Generated from 03_workflow_model.yaml; do not edit.", ""]
    lines += [f"entity({entity})." for entity in model.entities]
    lines += [f"recovery_entity({target})." for _, target in model.recovery]
    lines += [""]
    recovery_entities = {target for _, target in model.recovery}
    for entity in model.entities:
        if entity in recovery_entities:
            continue
        requirements = [source for source, target in model.dependencies if target == entity]
        lines.append(f"depends({entity}, [{', '.join(requirements)}]).")
    lines += [""]
    lines += [f"recovery({source}, {target})." for source, target in model.recovery]
    lines += [f"recover_on({source}, {reason}, {target})." for source, reason, target in model.recovery_triggers]
    lines += [f"observe_after({entity})." for entity in model.observe_after]
    if model.promotion_gate:
        lines.append(f"gate_before({model.promotion_gate[0]}, {model.promotion_gate[1]}).")
    lines.append(f"final_phase({model.final_entity}).")
    lines += [f"observe_before({target}, {source})." for target, source in model.observations]
    lines += [""]
    lines += [f"required({entity})." for entity in model.required_entities]
    lines += [""]
    lines += [f"achievement({item.entity}, {item.value})." for item in model.achievements]
    lines += [f"max_duration({item.entity}, {item.value})." for item in model.maintenance if item.property == "duration"]
    lines += [f"require_healthy({item.entity})." for item in model.maintenance if item.property == "health"]
    lines += [f"avoid_missing({item.entity}, {item.required})." for item in model.avoidance]
    lines += ["", f"duration_unit({model.duration_unit}).", f"max_retries({model.max_retries}).", ""]
    lines += ["// Controller-derived attempt counters."]
    lines += [f"attempt_count({entity}, 0)." for entity in model.entities]
    return "\n".join(lines) + "\n"


def transform(pipeline: Path, goals: Path, workflow: Path, beliefs: Path,
              agent: Path | None = None, generic: Path | None = None,
              project: Path | None = None) -> Model:
    model = parse_model(pipeline, goals, project)
    workflow.write_text(workflow_yaml(model), encoding="utf-8", newline="\n")
    generated_beliefs = project_beliefs(model)
    beliefs.write_text(generated_beliefs, encoding="utf-8", newline="\n")
    if agent is not None:
        if generic is None:
            raise ModelError("agent output requires a generic reasoning template")
        agent.write_text(generated_beliefs + "\n" + generic.read_text(encoding="utf-8"),
                         encoding="utf-8", newline="\n")
    return model


def generation_manifest(pipeline: Path, goals: Path, project: Path | None, model: Model) -> str:
    def digest(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    inputs = {"pipeline": {"path": str(pipeline), "sha256": digest(pipeline)},
              "goal": {"path": str(goals), "sha256": digest(goals)}}
    if project is not None:
        inputs["project"] = {"path": str(project), "sha256": digest(project)}
    return json.dumps({"inputs": inputs, "required_entities": list(model.required_entities),
                       "achievements": [item.entity for item in model.achievements]}, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pipeline", type=Path, default=Path("01_pipeline.yaml"))
    parser.add_argument("--goals", type=Path, default=Path("02_goal.yaml"))
    parser.add_argument("--workflow", type=Path, default=Path("03_workflow_model.yaml"))
    parser.add_argument("--beliefs", type=Path, default=Path("bdi_project.asl"))
    parser.add_argument("--agent", type=Path, default=Path("bdi_agent.asl"))
    parser.add_argument("--generic", type=Path, default=Path("bdi_generic.asl"))
    parser.add_argument("--project", type=Path, help="project job-role mapping for a real GitHub Actions workflow")
    parser.add_argument("--manifest-output", type=Path, help="write input hashes and active goal closure as JSON")
    args = parser.parse_args()
    try:
        model = transform(args.pipeline, args.goals, args.workflow, args.beliefs,
                           args.agent, args.generic, args.project)
        if args.manifest_output is not None:
            args.manifest_output.write_text(
                generation_manifest(args.pipeline, args.goals, args.project, model),
                encoding="utf-8", newline="\n")
    except ModelError as exc:
        parser.error(str(exc))
    print(f"generated {args.workflow} and {args.beliefs} ({len(model.entities)} entities)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
