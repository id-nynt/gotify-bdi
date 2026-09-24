package harness;

import telemetry.Observation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub-specific implementation behind the generic WorkflowExecutor boundary. */
public final class GitHubActionsWorkflowExecutor implements WorkflowExecutor {
    private static final Logger LOG = Logger.getLogger(GitHubActionsWorkflowExecutor.class.getName());
    private static final Set<String> ENTITIES = Set.of(
        "build", "test", "security", "staging", "production", "rollback_production");
    private static final Pattern RUN_ID = Pattern.compile("\\\"(?:workflow_run_id|id)\\\"\\s*:\\s*(\\d+)");
    private static final Pattern STATUS = Pattern.compile("\\\"status\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern CONCLUSION = Pattern.compile("\\\"conclusion\\\"\\s*:\\s*(?:\\\"([^\\\"]*)\\\"|null)");

    public record Config(URI apiBase, String repository, String workflowFile, String token,
                         String ref, Duration pollInterval, Duration maxWait) {
        public Config {
            if (apiBase == null || repository == null || workflowFile == null || token == null || ref == null) {
                throw new IllegalArgumentException("GitHub API configuration is incomplete");
            }
            if (pollInterval.isNegative() || pollInterval.isZero() || maxWait.isNegative() || maxWait.isZero()) {
                throw new IllegalArgumentException("pollInterval and maxWait must be positive");
            }
        }
    }

    private final Config config;
    private final HttpClient client;
    private final Consumer<Observation> observationSink;
    private final CorrelationRegistry correlationRegistry;
    private final StructuredEventLogger structuredLogger;
    private final ExperimentExecutionPlan experimentPlan;
    private volatile long lastSuccessfulBuildRunId;

    public GitHubActionsWorkflowExecutor(Config config, Consumer<Observation> observationSink) {
        this(config, observationSink, null, null);
    }

    public GitHubActionsWorkflowExecutor(Config config, Consumer<Observation> observationSink,
                                         CorrelationRegistry correlationRegistry,
                                         StructuredEventLogger structuredLogger) {
        this.config = config;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.observationSink = observationSink == null ? observation -> { } : observationSink;
        this.correlationRegistry = correlationRegistry;
        this.structuredLogger = structuredLogger;
        this.experimentPlan = new ExperimentExecutionPlan();
    }

    /** Dispatches one atomic entity and waits for that exact GitHub run to finish. */
    @Override
    public void runJob(String entity) throws IOException, InterruptedException {
        validateEntity(entity);
        String executionId = UUID.randomUUID().toString();
        String sourceRunId = entity.equals("rollback_production") ? rollbackSourceRunId() : "";
        ExperimentExecutionPlan.Injection injection = experimentPlan.next(entity);
        String experimentId = property("BDI_EXPERIMENT_ID", "experiment-local");
        String releaseId = property("BDI_RELEASE_ID", config.ref());
        Instant started = Instant.now();
        long runId = dispatch(entity, executionId, sourceRunId, experimentId, releaseId, injection);
        CorrelationContext context = new CorrelationContext(
            experimentId, releaseId, entity, executionId, runId,
            environmentFor(entity));
        if (correlationRegistry != null) correlationRegistry.put(context);
        structured("bdi_decision", context, java.util.Map.of(
            "triggered_goal_event", "run_entity(" + entity + ")",
            "selected_plan", "run_entity",
            "selected_next_action", "run_job(" + entity + ")",
            "relevant_beliefs", correlationRegistry == null ? "{}" : correlationRegistry.decisionBeliefs(entity)));
        structured("github_dispatch_accepted", context, java.util.Map.of(
            "workflow_file", config.workflowFile(), "ref", config.ref(),
            "failure_mode", injection.failureMode(),
            "force_error_rate", injection.forceErrorRate(),
            "extra_latency_ms", injection.extraLatencyMs(),
            "execution_delay_ms", injection.executionDelayMs()));
        structured("github_execution_started", context, java.util.Map.of());
        LOG.info(() -> "github_execution_started entity=" + entity
            + " execution_id=" + executionId + " run_id=" + runId);

        TerminalResult result = poll(runId, started.plus(config.maxWait()));
        long durationMs = Duration.between(started, Instant.now()).toMillis();
        observationSink.accept(new Observation(entity, "execution_status", result.status(), Instant.now()));
        observationSink.accept(new Observation(entity, "duration", durationMs, Instant.now()));
        LOG.info(() -> "github_execution_finished entity=" + entity
            + " execution_id=" + executionId + " run_id=" + runId
            + " status=" + result.status() + " duration_ms=" + durationMs);
        structured("execution_terminal", context, java.util.Map.of(
            "status", result.status(), "duration_ms", durationMs));

        if (entity.equals("build") && result.status().equals("success")) {
            lastSuccessfulBuildRunId = runId;
        }
    }

    private long dispatch(String entity, String executionId, String sourceRunId,
                          String experimentId, String releaseId,
                          ExperimentExecutionPlan.Injection injection)
        throws IOException, InterruptedException {
        URI uri = endpoint("/repos/" + config.repository() + "/actions/workflows/"
            + config.workflowFile() + "/dispatches");
        String body = "{\"ref\":\"" + escape(config.ref())
            + "\",\"return_run_details\":true,\"inputs\":{\"entity\":\""
            + escape(entity) + "\",\"execution_id\":\"" + escape(executionId) + "\",\"source_run_id\":\""
            + escape(sourceRunId) + "\",\"failure_mode\":\"" + escape(injection.failureMode())
            + "\",\"force_error_rate\":\"" + escape(injection.forceErrorRate())
            + "\",\"extra_latency_ms\":\"" + escape(injection.extraLatencyMs())
            + "\",\"execution_delay_ms\":\"" + escape(injection.executionDelayMs())
            + "\",\"experiment_id\":\"" + escape(experimentId)
            + "\",\"release_id\":\"" + escape(releaseId) + "\"}}";
        HttpRequest request = request(uri)
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub workflow dispatch failed with HTTP " + response.statusCode()
                + ": " + response.body());
        }
        Matcher matcher = RUN_ID.matcher(response.body());
        if (!matcher.find()) {
            throw new IOException("GitHub dispatch response did not contain workflow_run_id");
        }
        return Long.parseLong(matcher.group(1));
    }

    private TerminalResult poll(long runId, Instant deadline) throws IOException, InterruptedException {
        URI uri = endpoint("/repos/" + config.repository() + "/actions/runs/" + runId);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = client.send(request(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GitHub workflow run lookup failed with HTTP " + response.statusCode());
            }
            Matcher statusMatcher = STATUS.matcher(response.body());
            Matcher conclusionMatcher = CONCLUSION.matcher(response.body());
            String status = statusMatcher.find() ? statusMatcher.group(1) : "";
            String conclusion = conclusionMatcher.find() ? conclusionMatcher.group(1) : "";
            if (status.equals("completed")) return new TerminalResult(normalizeConclusion(conclusion));
            Thread.sleep(config.pollInterval().toMillis());
        }
        LOG.warning(() -> "github_execution_timeout run_id=" + runId);
        return new TerminalResult("timeout");
    }

    private String rollbackSourceRunId() throws IOException {
        long runId = lastSuccessfulBuildRunId;
        if (runId == 0) throw new IOException("No successful build run is available for production rollback");
        return Long.toString(runId);
    }

    private void structured(String event, CorrelationContext context, java.util.Map<String, ?> fields) {
        if (structuredLogger != null) structuredLogger.event(event, context, fields);
    }

    private static String property(String name, String fallback) {
        String value = System.getProperty(name.toLowerCase());
        if (value == null || value.isBlank()) value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String environmentFor(String entity) {
        return switch (entity) {
            case "staging" -> "staging";
            case "production", "rollback_production" -> "production";
            default -> "ci";
        };
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + config.token())
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("Content-Type", "application/json");
    }

    private URI endpoint(String path) {
        return URI.create(config.apiBase().toString().replaceAll("/$", "") + path);
    }

    private static String normalizeConclusion(String conclusion) {
        return switch (conclusion) {
            case "success" -> "success";
            case "cancelled" -> "cancelled";
            case "timed_out" -> "timeout";
            default -> "fail";
        };
    }

    private static void validateEntity(String entity) {
        if (!ENTITIES.contains(entity)) throw new IllegalArgumentException("Unsupported workflow entity: " + entity);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record TerminalResult(String status) { }
}
