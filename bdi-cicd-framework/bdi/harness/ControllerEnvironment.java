package harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import jason.asSyntax.Literal;
import jason.asSyntax.Structure;
import jason.environment.Environment;

/** Jason environment transporting generated decisions to one-entity execution and telemetry adapters. */
public final class ControllerEnvironment extends Environment {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private ControllerProjectConfig controller;
    private ProjectConfig telemetryProject;
    private EntityExecution executor;
    private StructuredEventLogger journal;
    private final Map<String, EntityExecution.Result> latest = new LinkedHashMap<>();
    private Path resultFile;
    private String scenario;
    private String verifiedBaselineSha = "";
    private long productionFinishedAt;
    private com.fasterxml.jackson.databind.JsonNode reconsiderationConfig;
    private final java.util.Set<String> reconsidering = new java.util.HashSet<>();
    private com.fasterxml.jackson.databind.JsonNode repairConfig;
    private com.fasterxml.jackson.databind.JsonNode diagnosticBindings;
    private final Map<String,Long> repairStarted = new LinkedHashMap<>();
    private final Map<String,Boolean> repairCompleted = new LinkedHashMap<>();
    private final Map<String,Object> repairEvidence = new LinkedHashMap<>();
    private final java.util.Set<String> operationsStarted = new java.util.HashSet<>();
    private final Map<String, Integer> attempts = new LinkedHashMap<>();
    private final Map<String, Integer> observationRounds = new LinkedHashMap<>();
    private final Map<String, String> telemetry = new LinkedHashMap<>();
    private final Map<String, Long> observationStarted = new LinkedHashMap<>();

    @Override
    public void init(String[] args) {
        super.init(args);
        try {
            Path projectFile = Path.of(required("BDI_PROJECT_FILE"));
            controller = ControllerProjectConfig.load(projectFile);
            var document=JSON.valueToTree(new org.yaml.snakeyaml.Yaml().load(Files.readString(projectFile)));
            repairConfig=document.path("candidate_repair");
            reconsiderationConfig=document.path("rollback_reconsideration");
            diagnosticBindings=document.path("bindings").path("diagnostics");
            resultFile = Path.of(value("BDI_RESULT_FILE", "build/controller-result.json"));
            journal = new StructuredEventLogger(Path.of(value("BDI_JOURNAL_FILE", "build/controller-journal.jsonl")));
            scenario = System.getenv("BDI_SCENARIO");
            if (!controller.project().equals("gotify") && !value("BDI_KNOWN_GOOD_SHA", "").isBlank()) addPercept(Literal.parseLiteral("known_good_available"));
            if (scenario == null || scenario.isBlank()) {
                if (!controller.environments().isEmpty()) telemetryProject = ProjectConfig.load(projectFile);
                executor = new GitHubEntityExecution(controller, journal);
            } else {
                executor = new ScenarioEntityExecution(scenario);
            }
            journal.event("controller_started", null, Map.of("project", controller.project(),
                "mode", scenario == null || scenario.isBlank() ? "github" : "scenario",
                "scenario", scenario == null ? "" : scenario));
            if (Boolean.parseBoolean(value("BDI_GUI", "false"))) {
                journal.event("mas_console", null, Map.of("visible",
                    jason.runtime.MASConsoleGUI.get().getFrame().isShowing(),
                    "agent", "controller_agent", "keep_open", true));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Cannot start BDI controller: " + error.getMessage(), error);
        }
    }

    @Override
    public synchronized boolean executeAction(String agentName, Structure action) {
        try {
            if (controller.project().equals("gotify") && productionFinishedAt != 0
                    && java.util.Set.of("run_job", "diagnose_candidate", "restart_candidate", "observe_telemetry").contains(action.getFunctor())
                    && java.util.Set.of("production", "rollback").contains(atom(action, 0))
                    && publishPostdeployClock()) return true;
            return switch (action.getFunctor()) {
                case "record_decision" -> {
                    journal.event("bdi_decision", null, Map.of("entity", atom(action, 0),
                        "decision", atom(action, 1), "counter", integer(action, 2)));
                    yield true;
                }
                case "begin_reconsideration" -> {
                    String entity=atom(action,0);
                    if (!reconsiderationConfig.has(entity) || !latest.containsKey(entity)
                            || !latest.get(entity).status().equals("success") || !reconsidering.add(entity))
                        throw new IllegalStateException("Unconfigured or repeated reconsideration");
                    removePerceptsByUnif(Literal.parseLiteral("telemetry_measurement("+entity+",_,_,_,_,_,_,_,_)"));
                    observationStarted.remove(entity); telemetry.remove(entity);
                    journal.event("rollback_selected",null,Map.of("entity",entity,"execution_id",latest.get(entity).executionId()));
                    yield true;
                }
                case "run_job" -> runJob(action);
                case "diagnose_candidate" -> candidateOperation(action,false);
                case "restart_candidate" -> candidateOperation(action,true);
                case "observe_telemetry" -> observeTelemetry(action);
                case "reconcile_job" -> reconcileJob(action);
                case "accept_telemetry" -> {
                    telemetry.put(atom(action, 0), atom(action, 1));
                    journal.event("health_accepted", null, Map.of("entity", atom(action, 0), "decision", atom(action, 1)));
                    yield true;
                }
                case "finish" -> finish(action);
                case "record_recovery" -> {
                    journal.event("bdi_recovery_decision", null, Map.of("source", atom(action, 0),
                        "entity", atom(action, 1), "reason", atom(action, 2),
                        "known_good_sha", value("BDI_KNOWN_GOOD_SHA", "")));
                    yield true;
                }
                default -> false;
            };
        } catch (Exception error) {
            journal.event("controller_action_error", null, Map.of("action", action.toString(),
                "error", String.valueOf(error.getMessage())));
            if (java.util.Set.of("diagnose_candidate","restart_candidate").contains(action.getFunctor()) && action.getArity() == 2) {
                // An adapter exception cannot prove a remote operation has terminated.
                String percept=action.getFunctor().equals("restart_candidate")?"candidate_restarted":"candidate_diagnosed";
                addPercept(Literal.parseLiteral(percept+"("+atom(action,0)+","+integer(action,1)+",execution_unknown)"));
                informAgsEnvironmentChanged();
                return true;
            }
            if (action.getFunctor().equals("run_job") && action.getArity() >= 2) {
                addPercept(Literal.parseLiteral("status(" + atom(action, 0) + "," + integer(action, 1) + ",unknown)"));
                informAgsEnvironmentChanged();
                return true;
            }
            return false;
        }
    }

    private boolean candidateOperation(Structure action, boolean restart) throws Exception {
        String entity=atom(action,0);
        int attempt=integer(action,1);
        var rule=repairConfig.path(entity);
        if (rule.isMissingNode() || !latest.containsKey(entity)) throw new IllegalArgumentException("Unconfigured repair target");
        repairStarted.putIfAbsent(entity,System.nanoTime());
        String outcome="unknown";
        String operation=(restart?"restart_":"diagnose_")+entity;
        if (attempt != 1 || !operationsStarted.add(operation)) throw new IllegalStateException("Repair operation already attempted or exceeds budget");
        long remaining=rule.path("deadline_seconds").asLong()-(System.nanoTime()-repairStarted.get(entity))/1_000_000_000;
        if (remaining <= 0) outcome="exhausted";
        else if (scenario!=null && !scenario.isBlank()) {
            journal.event(restart?"repair_started":"diagnosis_started",null,Map.of("entity",entity,"attempt",attempt,"deployment_execution_id",latest.get(entity).executionId()));
            if (restart) outcome=scenario.equals("candidate_repair_unknown")?"execution_unknown":scenario.equals("candidate_restart_fails")?"failed":"executed";
            else outcome=java.util.Set.of("candidate_stopped","candidate_restart_fails","candidate_repair_unknown").contains(scenario)?"app_stopped":"not_applicable";
        } else {
            var binding=diagnosticBindings.path(entity);
            var inputs=Map.of("target_execution_id",latest.get(entity).executionId(),"diagnostic_binding",JSON.writeValueAsString(binding),
                "verification_seconds",rule.path("verification_window_seconds").asText());
            journal.event(restart?"repair_started":"diagnosis_started",null,Map.of("entity",entity,"attempt",attempt,
                "deployment_execution_id",latest.get(entity).executionId()));
            EntityExecution.Result operationResult=((GitHubEntityExecution)executor).executeOperation(operation,attempt,inputs,java.time.Duration.ofSeconds(remaining));
            repairEvidence.put(operation,operationResult);
            if (operationResult.status().equals("unknown")) outcome="execution_unknown";
            else if (!operationResult.status().equals("success")) outcome="failed";
            else {
                try {
                    var evidence=((GitHubEntityExecution)executor).operationEvidence(operationResult);
                    outcome=classifyRepairEvidence(evidence,latest.get(entity).executionId(),restart);
                } catch (Exception error) {
                    journal.event("repair_evidence_error",null,Map.of("entity",entity,"reason",error.toString()));
                }
            }
        }
        if (restart && outcome.equals("executed")) {
            repairCompleted.put(entity,true);
            // Fresh verification samples get a new observation window, bounded by the total repair deadline.
            observationStarted.remove(entity); telemetry.remove(entity);
        }
        journal.event(restart?"repair_finished":"diagnosis_finished",null,Map.of("entity",entity,"attempt",attempt,"status",outcome,
            "deployment_execution_id",latest.get(entity).executionId()));
        addPercept(Literal.parseLiteral((restart?"candidate_restarted":"candidate_diagnosed")+"("+entity+","+attempt+","+outcome+")"));
        informAgsEnvironmentChanged();
        return true;
    }

    static String classifyRepairEvidence(com.fasterxml.jackson.databind.JsonNode evidence,String expected,boolean restart) {
        var before=evidence.path("before");
        if (!expected.equals(evidence.path("expected_execution_id").asText())
                || !expected.equals(before.path("deployment_execution_id").asText())
                || before.path("container_id").asText().isBlank()
                || !before.path("dependency_ready").isBoolean()
                || !(restart?"restart":"diagnose").equals(evidence.path("action").asText())) return "unknown";
        if (!restart) {
            if (!"observed".equals(evidence.path("status").asText())
                    || !java.util.Set.of("running","stopped").contains(before.path("app_state").asText())) return "unknown";
            return before.path("app_state").asText().equals("stopped") && before.path("dependency_ready").asBoolean()?"app_stopped":"not_applicable";
        }
        String status=evidence.path("status").asText();
        if (status.equals("executed") && (!expected.equals(evidence.path("after").path("deployment_execution_id").asText())
                || !before.path("container_id").asText().equals(evidence.path("after").path("container_id").asText()))) return "unknown";
        return java.util.Set.of("executed","failed","not_applicable").contains(status)?status:"unknown";
    }

    private boolean runJob(Structure action) throws Exception {
        if (action.getArity() != 2) return false;
        String entity = atom(action, 0);
        int attempt = integer(action, 1);
        attempts.put(entity, attempt);
        observationRounds.remove(entity);
        observationStarted.remove(entity);
        telemetry.remove(entity);
        if (!controller.jobNames().containsKey(entity)) throw new IllegalArgumentException("Unmapped entity " + entity);
        journal.event("bdi_decision", null, Map.of("decision", "run", "entity", entity,
            "attempt", attempt, "relevant_beliefs", controller.releaseSources().containsKey(entity)
                ? "recovery_trigger_known_good_and_single_attempt" : "dependencies_satisfied_and_goal_required"));
        journal.event("entity_execution_started", null, Map.of("entity", entity, "attempt", attempt));
        EntityExecution.Result result = executor.execute(entity, attempt);
        latest.put(entity, result);
        if (controller.project().equals("gotify") && entity.equals("production") && !result.status().equals("unknown")) {
            productionFinishedAt = System.nanoTime();
            if (result.status().equals("success") && executor instanceof GitHubEntityExecution) {
                Path clock = Path.of(System.getProperty("user.home"), "gotify-study-runtime", "trials", "bdi",
                    required("BDI_CAMPAIGN_ID"), "evidence", "production-boundary.json");
                long elapsed = deploymentElapsed(JSON.readTree(Files.readString(clock)),
                    required("BDI_CAMPAIGN_ID"), result.executionId(), Instant.now());
                productionFinishedAt -= elapsed * 1_000_000;
                journal.event("production_boundary_clock", null, Map.of("elapsed_ms", elapsed,
                    "execution_id", result.executionId(), "source", clock.toString()));
            }
        }
        if (controller.project().equals("gotify") && entity.equals("prepare") && result.status().equals("success")
                && executor instanceof GitHubEntityExecution github) {
            github.verifiedPackagedBaseline();
            verifiedBaselineSha = required("BDI_RELEASE_SHA");
            addPercept(Literal.parseLiteral("known_good_available"));
            journal.event("baseline_verified", null, Map.of("execution_id", result.executionId(), "release", "v1"));
        }
        journal.event("entity_execution_finished", null, Map.of("entity", entity, "attempt", attempt,
            "execution_id", result.executionId(), "github_run_id", result.githubRunId(),
            "run_url", result.runUrl(), "status", result.status(), "duration_ms", result.durationMs()));
        if ("success".equals(result.status()) && java.util.Arrays.asList(value("BDI_PAUSE_AFTER_ENTITY", "").split(",")).contains(entity)) {
            long milliseconds = Long.parseLong(value("BDI_PAUSE_MILLISECONDS", "0"));
            journal.event("controller_pause", null, Map.of("after_entity", entity,
                "milliseconds", milliseconds, "successor_dispatched", false));
            if (milliseconds > 0) Thread.sleep(milliseconds);
        }
        addPercept(Literal.parseLiteral("duration(" + entity + "," + attempt + "," + result.durationMs() + ")"));
        addPercept(Literal.parseLiteral("status(" + entity + "," + attempt + "," + result.status() + ")"));
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean reconcileJob(Structure action) throws Exception {
        if (action.getArity() != 3) return false;
        String entity = atom(action, 0);
        int attempt = integer(action, 1), round = integer(action, 2);
        EntityExecution.Result result;
        try { result = executor.reconcile(entity, attempt); }
        catch (Exception error) { result = new EntityExecution.Result("unknown", 0, "unresolved", 0, ""); }
        latest.put(entity, result);
        removePerceptsByUnif(Literal.parseLiteral("duration(" + entity + "," + attempt + ",_)"));
        journal.event("bdi_reconciliation", null, Map.of("entity", entity, "attempt", attempt, "round", round,
            "execution_id", result.executionId(), "github_run_id", result.githubRunId(), "status", result.status()));
        addPercept(Literal.parseLiteral("duration(" + entity + "," + attempt + "," + result.durationMs() + ")"));
        addPercept(Literal.parseLiteral("reconciled(" + entity + "," + attempt + "," + round + "," + result.status() + ")"));
        informAgsEnvironmentChanged();
        return true;
    }

    private boolean observeTelemetry(Structure action) throws Exception {
        if (action.getArity() != 1) return false;
        String entity = atom(action, 0);
        journal.event("bdi_decision", null, Map.of("decision", "observe", "entity", entity));
        String decision;
        String reason;
        int round = observationRounds.merge(entity, 1, Integer::sum);
        observationStarted.putIfAbsent(entity, System.nanoTime());
        ProjectTelemetryProvider.Measurement measurement;
        if (scenario != null && !scenario.isBlank()) {
            boolean recovery = controller.releaseSources().containsKey(entity);
            boolean protectedEntity = !recovery && controller.releaseSources().keySet().stream()
                .anyMatch(r -> controller.environments().get(r).equals(controller.environments().get(entity)));
            if (java.util.Set.of("candidate_stopped","candidate_restart_fails","candidate_repair_unknown").contains(scenario) && entity.equals("production") && !repairCompleted.getOrDefault(entity,false)) { decision="block"; reason="scenario_candidate_stopped"; }
            else if (scenario.equals("rollback_reconsideration") && entity.equals("production")) { decision=reconsidering.contains(entity)?"allow":"block"; reason="scenario_reconsideration"; }
            else if (scenario.equals("rollback_unhealthy") && recovery) { decision = "block"; reason = "scenario_recovery_unhealthy"; }
            else if (scenario.equals("rollback_unknown") && recovery) { decision = "unknown"; reason = "scenario_recovery_unavailable"; }
            else if (scenario.equals("production_unknown") && protectedEntity) { decision = "unknown"; reason = "scenario_production_unavailable"; }
            else if (protectedEntity && java.util.Set.of("production_unhealthy", "rollback_failure", "rollback_unknown", "rollback_unhealthy").contains(scenario)) {
                decision = "block"; reason = "scenario_production_unhealthy";
            }
            else if (scenario.equals("telemetry_block")) { decision = "block"; reason = "scenario_block"; }
            else if ((scenario.equals("telemetry_transient") || (scenario.equals("production_transient") && protectedEntity)) && round == 1) {
                decision = "block"; reason = "scenario_temporary_fault";
            }
            else if (scenario.equals("telemetry_flapping") && round % 2 == 0) { decision = "block"; reason = "scenario_flapping"; }
            else if (scenario.equals("telemetry_unknown") || (scenario.equals("telemetry_delayed") && round == 1)) {
                decision = "unknown"; reason = "scenario_wait";
            }
            else {
                decision = "allow"; reason = "scenario_healthy";
            }
        } else {
            EntityExecution.Result execution = latest.get(entity);
            String environment = controller.environments().get(entity);
            if (execution == null || environment == null) throw new IllegalStateException("No deployment identity/environment for " + entity);
            var provider = new ProjectTelemetryProvider(telemetryProject, environment, entity, execution.executionId());
            measurement = (repairCompleted.getOrDefault(entity,false) || reconsidering.contains(entity)) ? provider.measureRepair() : provider.measure();
            return publishMeasurement(entity, round, measurement, execution.executionId());
        }
        measurement = reason.equals("scenario_candidate_stopped")
            ? new ProjectTelemetryProvider.Measurement("fresh", "not_ready", 0, 0, 0)
            : decision.equals("unknown")
            ? new ProjectTelemetryProvider.Measurement("unavailable", "unknown", 0, 0, 0)
            : new ProjectTelemetryProvider.Measurement("fresh", "ready", decision.equals("block") ? 1 : 0, 20, 1);
        return publishMeasurement(entity, round, measurement, latest.get(entity).executionId());
    }

    private boolean publishMeasurement(String entity, int round, ProjectTelemetryProvider.Measurement m, String executionId) {
        int attempt = attempts.getOrDefault(entity, 0);
        long elapsed = (System.nanoTime() - observationStarted.get(entity)) / 1_000_000;
        if (controller.project().equals("gotify") && productionFinishedAt != 0
                && java.util.Set.of("production", "rollback").contains(entity) && publishPostdeployClock()) elapsed = 3_600_001;
        if ("observation_deadline".equals(scenario)) elapsed = 3_600_001;
        if (repairStarted.containsKey(entity) && (System.nanoTime()-repairStarted.get(entity))/1_000_000_000 >= repairConfig.path(entity).path("deadline_seconds").asInt()) elapsed=3_600_001;
        journal.event("observation_clock", null, Map.of("entity", entity, "round", round, "elapsed_ms", elapsed));
        journal.event("telemetry_measurement", null, Map.of("entity", entity, "attempt", attempt, "round", round,
            "execution_id", executionId, "data_status", m.dataStatus(), "readiness", m.readiness(),
            "error_rate", m.errorRate(), "latency_p95_ms", m.latencyP95Ms(), "availability", m.availability()));
        addPercept(Literal.parseLiteral("telemetry_measurement(" + entity + "," + attempt + "," + round + ","
            + m.dataStatus() + "," + m.readiness() + "," + m.errorRate() + "," + m.latencyP95Ms() + "," + m.availability() + "," + elapsed + ")"));
        informAgsEnvironmentChanged();
        return true;
    }

    static long deploymentElapsed(com.fasterxml.jackson.databind.JsonNode clock, String trial, String execution, Instant now) {
        if (!clock.path("trial").asText().equals(trial) || !clock.path("approach").asText().equals("bdi")
                || !clock.path("execution_id").asText().equals(execution) || !clock.path("release").asText().equals("v2")
                || !clock.path("ready_at_unix").isNumber()) throw new IllegalArgumentException("Invalid production boundary clock");
        double timestamp = clock.path("ready_at_unix").asDouble();
        if (!Double.isFinite(timestamp) || timestamp <= 0 || timestamp * 1000 > now.toEpochMilli() + 1000)
            throw new IllegalArgumentException("Invalid production boundary timestamp");
        return Math.max(0, now.toEpochMilli() - (long)(timestamp * 1000));
    }

    private boolean publishPostdeployClock() {
        long elapsed = (System.nanoTime() - productionFinishedAt) / 1_000_000;
        long limit = repairConfig.path("production").path("deadline_seconds").asLong() * 1000;
        removePerceptsByUnif(Literal.parseLiteral("postdeploy_elapsed(production,_)"));
        addPercept(Literal.parseLiteral("postdeploy_elapsed(production," + elapsed + ")"));
        journal.event("postdeploy_clock", null, Map.of("elapsed_ms", elapsed, "limit_ms", limit));
        informAgsEnvironmentChanged();
        return elapsed >= limit;
    }

    private boolean finish(Structure action) throws Exception {
        if (action.getArity() != 2) return false;
        String outcome = atom(action, 0);
        String recoveryOutcome = atom(action, 1);
        Files.createDirectories(resultFile.toAbsolutePath().getParent());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mechanism", "bdi");
        result.put("campaign_id", value("BDI_CAMPAIGN_ID", ""));
        result.put("generation_manifest", value("BDI_MANIFEST_FILE", ""));
        result.put("timestamp", Instant.now().toString());
        result.put("outcome", outcome);
        result.put("recovery_outcome", recoveryOutcome);
        result.put("project", controller.project());
        result.put("mode", scenario == null || scenario.isBlank() ? "github" : "scenario");
        result.put("repository", value("GITHUB_REPOSITORY", ""));
        result.put("release_sha", value("BDI_RELEASE_SHA", ""));
        result.put("known_good_sha", verifiedBaselineSha.isBlank() ? value("BDI_KNOWN_GOOD_SHA", "") : verifiedBaselineSha);
        result.put("telemetry", telemetry);
        result.put("executions", latest);
        result.put("candidate_repair_operations",repairEvidence);
        var requested = JSON.readTree(value("BDI_GOALS", "[]"));
        var healthGoals = JSON.readTree(value("BDI_HEALTH_GOALS", "[]"));
        java.util.List<String> achieved = new java.util.ArrayList<>();
        java.util.List<String> unmet = new java.util.ArrayList<>();
        for (var goal : requested) {
            String entity = goal.isTextual() ? goal.asText() : goal.path("entity").asText();
            String desired = goal.isTextual() ? "success" : goal.path("status").asText();
            boolean healthyRequired = desired.equals("success") && controller.environments().containsKey(entity);
            for (var health : healthGoals) if (health.asText().equals(entity)) healthyRequired = true;
            boolean satisfied = latest.containsKey(entity) && latest.get(entity).status().equals(desired)
                && (!healthyRequired || "allow".equals(telemetry.get(entity)));
            // Restoring another revision never satisfies delivery of this candidate.
            if (!recoveryOutcome.equals("not_needed") && controller.environments().containsKey(entity)
                && controller.releaseSources().keySet().stream().anyMatch(r -> latest.containsKey(r)
                    && controller.environments().get(r).equals(controller.environments().get(entity)))) satisfied = false;
            (satisfied ? achieved : unmet).add(entity);
        }
        result.put("requested_goals", requested);
        result.put("goal_message", outcome.equals("achieved") ? "Declared goals achieved." : "Attempted but failed to achieve goals.");
        result.put("achieved_goals", achieved);
        result.put("unmet_goals", unmet);
        Map<String, Object> verified = new LinkedHashMap<>();
        boolean negativeExperiment = false;
        for (var goal : requested) if (goal.path("status").asText().equals("failure")) negativeExperiment = true;
        result.put("negative_goal_experiment", negativeExperiment);
        if (outcome.equals("achieved") && !negativeExperiment) for (var entry : latest.entrySet()) {
            if (entry.getValue().status().equals("success") && "allow".equals(telemetry.get(entry.getKey()))) {
                verified.put(entry.getKey(), Map.of("release_sha", value("BDI_RELEASE_SHA", ""),
                    "github_run_id", entry.getValue().githubRunId(), "execution_id", entry.getValue().executionId(),
                    "environment", controller.environments().get(entry.getKey())));
            }
        }
        result.put("verified_releases", verified);
        Files.writeString(resultFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
        journal.event("controller_finished", null, Map.of("outcome", outcome, "recovery_outcome", recoveryOutcome, "project", controller.project()));
        if (Boolean.parseBoolean(value("BDI_GUI", "false"))) {
            java.util.logging.Logger.getLogger(getClass().getName()).info(
                "Campaign finished: outcome=" + outcome + ", recovery=" + recoveryOutcome + ". Result: " + resultFile
                + ". Close MAS Console to exit; Gradle waits while the GUI is open. No further jobs will run.");
            return true;
        }
        Thread shutdown = new Thread(() -> {
            try { getEnvironmentInfraTier().getRuntimeServices().stopMAS(); }
            catch (Exception ignored) { stop(); }
        }, "controller-shutdown");
        shutdown.setDaemon(true);
        shutdown.start();
        return true;
    }

    private static String atom(Structure action, int index) {
        String value = action.getTerm(index).toString();
        if (!value.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("Expected atom: " + value);
        return value;
    }
    private static int integer(Structure action, int index) { return Integer.parseInt(action.getTerm(index).toString()); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
    private static String value(String name, String fallback) {
        String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value;
    }
}
