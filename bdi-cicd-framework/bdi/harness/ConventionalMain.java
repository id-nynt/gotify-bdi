package harness;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.time.Instant;
import java.util.*;

/** Procedural control loop over the same worker/adapters. Does not start Jason. */
public final class ConventionalMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    static String env(String key) { return System.getenv().getOrDefault(key, ""); }
    static Set<String> strings(JsonNode node) { Set<String> set = new HashSet<>(); node.forEach(v -> set.add(v.asText())); return set; }
    public static void main(String[] args) throws Exception {
        Path lockPath = Path.of(env("BDI_LOCK_FILE"));
        Files.createDirectories(lockPath.getParent());
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another controller holds " + lockPath);
            ControllerMain.verifyCampaign(Path.of(env("BDI_MANIFEST_FILE")));
            var p = JSON.readTree(Path.of(env("BDI_CONVENTIONAL_POLICY")).toFile());
            var x = p.path("execution");
            var settings = new ConventionalPolicy.Settings(x.path("max_retries").asInt(), x.path("retry_interval_seconds").asLong()*1000,
                x.path("observation_attempts").asInt(), x.path("observation_interval_seconds").asLong()*1000,
                x.path("observation_timeout_seconds").asLong()*1000, x.path("healthy_observations").asInt(),
                x.path("reconciliation_attempts").asInt(), x.path("reconciliation_interval_seconds").asLong()*1000,
                p.path("max_production_ms").asLong(), p.path("thresholds").path("error_rate_high_gt").asDouble(),
                p.path("thresholds").path("latency_p95_ms_high_gt").asDouble(), strings(x.path("retry_safe")), strings(p.path("recovery_triggers")));
            Path projectPath = Path.of(env("BDI_PROJECT_FILE"));
            var config = ControllerProjectConfig.load(projectPath);
            var project = ProjectConfig.load(projectPath);
            var journal = new StructuredEventLogger(Path.of(env("BDI_JOURNAL_FILE")));
            String scenario = env("BDI_SCENARIO");
            EntityExecution executor = scenario.isBlank() ? new GitHubEntityExecution(config, journal) : new ScenarioEntityExecution(scenario);
            journal.event("controller_started", null, Map.of("mode", scenario.isBlank() ? "github" : "scenario", "project", config.project(), "mechanism", "conventional"));
            ConventionalPolicy.IO io = new ConventionalPolicy.IO() {
                public void event(String name, Map<String, ?> fields) { journal.event(name, null, fields); }
                public void sleep(long ms) throws Exception { Thread.sleep(ms); }
                public EntityExecution.Result execute(String entity, int attempt) throws Exception {
                    event("entity_execution_started", Map.of("entity", entity, "attempt", attempt));
                    EntityExecution.Result result;
                    try { result = executor.execute(entity, attempt); }
                    catch (Exception error) { event("execution_exception", Map.of("entity", entity, "message", error.toString())); result = new EntityExecution.Result("unknown", 0, "unresolved", 0, ""); }
                    event("entity_execution_finished", Map.of("entity", entity, "attempt", attempt, "status", result.status(), "duration_ms", result.durationMs(),
                        "execution_id", result.executionId(), "github_run_id", result.githubRunId(), "run_url", result.runUrl()));
                    if ("success".equals(result.status()) && java.util.Arrays.asList(env("BDI_PAUSE_AFTER_ENTITY").split(",")).contains(entity)) {
                        long ms = Long.parseLong(env("BDI_PAUSE_MILLISECONDS"));
                        event("controller_pause", Map.of("after_entity", entity, "milliseconds", ms, "successor_dispatched", false));
                        sleep(ms);
                    }
                    return result;
                }
                public EntityExecution.Result reconcile(String entity, int attempt, int round) throws Exception {
                    EntityExecution.Result result;
                    try { result = executor.reconcile(entity, attempt); }
                    catch (Exception error) { result = new EntityExecution.Result("unknown", 0, "unresolved", 0, ""); }
                    event("conventional_reconciliation", Map.of("entity", entity, "attempt", attempt, "round", round, "status", result.status(), "execution_id", result.executionId(), "github_run_id", result.githubRunId()));
                    return result;
                }
                public ProjectTelemetryProvider.Measurement measure(String entity, int attempt, int round, String id) {
                    ProjectTelemetryProvider.Measurement m;
                    if (scenario.isBlank()) m = new ProjectTelemetryProvider(project, config.environments().get(entity), entity, id).measure();
                    else {
                        boolean bad = (scenario.equals("production_unhealthy") && entity.equals("production")) ||
                            (scenario.equals("telemetry_block") && entity.equals("staging")) ||
                            (scenario.equals("telemetry_transient") && round == 1) ||
                            (scenario.equals("production_transient") && entity.equals("production") && round == 1);
                        m = new ProjectTelemetryProvider.Measurement("fresh", "ready", bad ? 1 : 0, 20, 1);
                    }
                    event("telemetry_measurement", Map.of("entity", entity, "attempt", attempt, "round", round, "execution_id", id,
                        "data_status", m.dataStatus(), "readiness", m.readiness(), "error_rate", m.errorRate(), "latency_p95_ms", m.latencyP95Ms(), "availability", m.availability()));
                    return m;
                }
            };
            var policy = new ConventionalPolicy(settings, io, () -> System.nanoTime()/1_000_000, !env("BDI_KNOWN_GOOD_SHA").isBlank());
            var outcome = policy.run();
            Map<String, Object> verified = new LinkedHashMap<>();
            if (outcome.outcome().equals("achieved")) for (var entry : policy.latest.entrySet()) {
                if ("allow".equals(policy.telemetry.get(entry.getKey()))) verified.put(entry.getKey(), Map.of("release_sha", env("BDI_RELEASE_SHA"),
                    "execution_id", entry.getValue().executionId(), "github_run_id", entry.getValue().githubRunId(), "environment", config.environments().get(entry.getKey())));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("campaign_id", env("BDI_CAMPAIGN_ID")); result.put("mechanism", "conventional");
            result.put("timestamp", Instant.now().toString()); result.put("mode", scenario.isBlank() ? "github" : "scenario");
            result.put("project", config.project()); result.put("repository", env("GITHUB_REPOSITORY"));
            result.put("outcome", outcome.outcome()); result.put("recovery_outcome", outcome.recovery());
            result.put("release_sha", env("BDI_RELEASE_SHA")); result.put("known_good_sha", env("BDI_KNOWN_GOOD_SHA"));
            result.put("executions", policy.latest); result.put("telemetry", policy.telemetry); result.put("verified_releases", verified);
            result.put("negative_goal_experiment", false);
            result.put("generation_manifest", env("BDI_MANIFEST_FILE"));
            var goals = JSON.readTree(env("BDI_GOALS"));
            result.put("requested_goals", goals);
            var achieved = new ArrayList<String>(); var unmet = new ArrayList<String>();
            for (var goal : goals) {
                String entity = goal.path("entity").asText();
                boolean satisfied = policy.latest.containsKey(entity) && policy.latest.get(entity).status().equals("success")
                    && "allow".equals(policy.telemetry.get(entity)) && !(entity.equals("production") && policy.latest.containsKey("rollback"));
                (satisfied ? achieved : unmet).add(entity);
            }
            result.put("achieved_goals", achieved); result.put("unmet_goals", unmet);
            result.put("goal_message", outcome.outcome().equals("achieved") ? "Declared goals achieved." : "Attempted but failed to achieve goals.");
            Files.writeString(Path.of(env("BDI_RESULT_FILE")), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result)+"\n");
            journal.event("controller_finished", null, Map.of("outcome", outcome.outcome(), "recovery_outcome", outcome.recovery(), "project", config.project()));
            System.out.println("CONVENTIONAL_RESULT="+outcome.outcome()+" recovery="+outcome.recovery());
            if (!outcome.outcome().equals("achieved")) System.exit(outcome.outcome().equals("stopped") ? 1 : 2);
        }
    }
}
