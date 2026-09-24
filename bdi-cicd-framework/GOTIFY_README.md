# Gotify adaptation

Use this file for the Gotify experiment. The copied legacy payment README,
examples and run directories are historical material, not Gotify evidence.

The four engineer inputs are `models/01_pipeline.yaml`, `models/02_goal.yaml`,
`config/controller_policy.yaml` and `config/runtime_bindings.yaml`. Generate with
`python3 generate_project.py`; validate with `python3 run_controller.py --validate-only`.
Generated files are `models/03_workflow_model.yaml`, `bdi/controller_agent.asl`
and `models/generation-manifest.json`. Do not edit generated files directly.

The generated Jason agent selects build, test, package, baseline preparation,
staging and production, then observes and chooses bounded recovery or stopping.
`.github/workflows/entity-execution.yml` runs one selected entity per dispatch;
it has no successor DAG. The controller runs outside the runner's job slot.

`gotify_probe` invokes the shared Python operation directly. It checks trial,
release and deployment execution identity, observation age, HTTP results, exact
message content, persistent baseline content and the dashboard. No Prometheus
service or payment metric is used. Error rate and p95 latency describe the HTTP
requests in one probe batch, not arbitrary user traffic. Both controllers use
the thresholds and limits recorded in `experiment/policy.json` and the BDI policy.

The `prepare` worker creates a new baseline fixture, verifies v1 independently
in staging and production, and retains its database. Only its successful result
establishes the agent's known-good belief. Rollback selects the packaged v1 image,
retains data and requires fresh verification. Restored v1 is not candidate success.

Diagnostics inspect the identified container and run SQLite `PRAGMA quick_check`
through a read-only connection. Neither diagnosis nor a scenario chooses a
controller action. The generated agent chooses restart; the worker executes it.

Inherited payment fault-plan inputs are disabled for Gotify. Live scenario/fault
injection must remain in an external neutral harness. Unit-test fixtures are not
live scenario results. A healthy agent simulation is only a syntax/control smoke
test and must not be reported as a successful deployment.

Runtime, credentials and Docker data stay under `$HOME/gotify-study-runtime`.
Local controller provenance and evidence stay under the study's `results/bdi/`.
Only public `evidence/` receipts are uploaded; private state contains credentials.
