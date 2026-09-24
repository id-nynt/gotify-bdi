package harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Dispatches one configured entity workflow and validates the selected job's terminal result. */
public final class GitHubEntityExecution implements EntityExecution {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ControllerProjectConfig project;
    private final String repository;
    private final String token;
    private final String ref;
    private final String releaseSha;
    private String knownGoodSha = "";
    private Path stateFile;
    private ObjectNode pending;
    private final String campaignId;
    private final URI apiBase;
    private final HttpClient client;
    private final Duration pollInterval;
    private final Duration maxWait;
    private final StructuredEventLogger journal;
    private Map<String,String> operationInputs = Map.of();
    private Instant operationDeadline;
    private final ExperimentExecutionPlan experimentPlan = new ExperimentExecutionPlan();

    public GitHubEntityExecution(ControllerProjectConfig project, StructuredEventLogger journal) {
        this(project, journal, URI.create(value("GITHUB_API_URL", "https://api.github.com")),
            required("GITHUB_REPOSITORY"), required("GITHUB_TOKEN"), value("BDI_WORKFLOW_REF", "main"),
            required("BDI_RELEASE_SHA"), required("BDI_CAMPAIGN_ID"),
            Duration.ofSeconds(number("BDI_POLL_SECONDS", 5)),
            Duration.ofMinutes(number("BDI_ENTITY_TIMEOUT_MINUTES", 20)));
        knownGoodSha = value("BDI_KNOWN_GOOD_SHA", "");
        stateFile = Path.of(required("BDI_EXECUTION_STATE_FILE"));
        loadPending();
    }

    GitHubEntityExecution(ControllerProjectConfig project, StructuredEventLogger journal, URI apiBase,
                          String repository, String token, String ref, String releaseSha, String campaignId,
                          Duration pollInterval, Duration maxWait) {
        if (!releaseSha.matches("(?i)[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new IllegalArgumentException("BDI_RELEASE_SHA must be a full immutable commit hash");
        }
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("GITHUB_REPOSITORY must be owner/name");
        }
        if (token == null || token.isEmpty() || token.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IllegalArgumentException("GITHUB_TOKEN is missing or contains whitespace/control characters; re-enter it without printing its value");
        this.project = project;
        this.repository = repository;
        this.token = token;
        this.ref = ref;
        this.releaseSha = releaseSha;
        this.campaignId = campaignId;
        this.apiBase = apiBase;
        this.pollInterval = pollInterval;
        this.maxWait = maxWait;
        this.journal = journal;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    void useStateFile(Path path) throws Exception { stateFile = path; loadPending(); }

    void verifiedPackagedBaseline() { knownGoodSha = releaseSha; }

    private void loadPending() {
        try {
            if (stateFile != null && Files.exists(stateFile)) pending = (ObjectNode) JSON.readTree(Files.readString(stateFile));
        } catch (Exception error) { throw new IllegalStateException("Cannot read unresolved execution state", error); }
    }

    private void savePending() throws IOException {
        if (stateFile == null) return; // In-memory adapter used by isolated HTTP tests only.
        Files.createDirectories(stateFile.toAbsolutePath().getParent());
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.writeValueAsString(pending));
        try (var file = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) { file.force(true); }
        try { Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public Result execute(String entity, int attempt) throws Exception {
        if (pending != null) {
            journal.event("dispatch_blocked_unresolved", null, Map.of("entity", entity, "pending", pending.toString()));
            return unresolved();
        }
        String selectedSha = sourceFor(entity, releaseSha, knownGoodSha, project);
        String expectedJob = project.jobNames().get(entity);
        if (expectedJob == null) throw new IllegalArgumentException("Unmapped entity: " + entity);
        String executionId = UUID.randomUUID().toString();
        ExperimentExecutionPlan.Injection injection = project.project().equals("gotify")
            ? ExperimentExecutionPlan.Injection.none() : experimentPlan.next(entity);
        String experimentMode = !"0".equals(injection.forceErrorRate()) ? "high_error_rate" : injection.experimentMode();
        if (!java.util.Set.of("normal", "high_error_rate", "request_faults").contains(experimentMode)
                || !java.util.Set.of("none", "force_failure", "transient_failure", "service_unavailable", "infrastructure_failure", "deployment_timeout", "candidate_stopped").contains(injection.failureMode()))
            throw new IllegalArgumentException("Unsupported experiment mode or failure mode");
        journal.event("execution_configuration", null, Map.of("entity", entity, "attempt", attempt,
            "execution_id", executionId, "failure_mode", injection.failureMode(), "experiment_mode", experimentMode,
            "release_sha", selectedSha, "workflow_ref", ref));
        pending = JSON.createObjectNode().put("campaign_id", campaignId).put("entity", entity)
            .put("attempt", attempt).put("execution_id", executionId).put("release_sha", selectedSha)
            .put("repository", repository).put("workflow_file", project.workflowFile()).put("api_base", apiBase.toString())
            .put("expected_job", expectedJob).put("started", Instant.now().toString()).put("run_id", 0);
        // Persist BEFORE sending. A crash or lost response must never silently permit another deployment.
        savePending();
        journal.event("dispatch_intent", null, Map.of("campaign_id", campaignId, "entity", entity,
            "attempt", attempt, "execution_id", executionId, "release_sha", selectedSha));
        try {
            long runId = dispatch(entity, attempt, executionId, injection.failureMode(), experimentMode, selectedSha);
            pending.put("run_id", runId); savePending();
            journal.event("dispatch_acknowledged", null, Map.of("execution_id", executionId, "github_run_id", runId));
            return settle(awaitSelectedJob(runId, expectedJob, operationDeadline == null ? Instant.now().plus(maxWait) : operationDeadline));
        } catch (DispatchRejected error) {
            pending.put("dispatch_rejected_http_status", error.status);
            savePending(); // A restart can settle this rejection without searching for a nonexistent run.
            journal.event("dispatch_rejected", null, Map.of("execution_id", executionId,
                "http_status", error.status, "reason", error.getMessage(),
                "next_step", "Check controller GITHUB_TOKEN repository/Actions write access and workflow ref"));
            return settle("dispatch_rejected");
        } catch (Exception error) {
            journal.event("execution_uncertain", null, Map.of("execution_id", executionId, "reason", String.valueOf(error.getMessage())));
            return unresolved();
        }
    }

    @Override
    public Result reconcile(String entity, int attempt) throws Exception {
        if (pending == null || !campaignId.equals(pending.path("campaign_id").asText())
            || !entity.equals(pending.path("entity").asText()) || attempt != pending.path("attempt").asInt()) return unresolved();
        return reconcilePending();
    }

    /** Read-only remote reconciliation; never sends another dispatch. Also usable after a process restart. */
    public Result reconcilePending() throws Exception {
        if (pending == null) throw new IllegalStateException("No unresolved execution");
        if (!repository.equals(pending.path("repository").asText()) || !apiBase.toString().equals(pending.path("api_base").asText()))
            throw new IllegalStateException("Pending execution belongs to another repository/API");
        try {
            if (pending.path("run_id").asLong() == 0 && rejectedStatus(pending.path("dispatch_rejected_http_status").asInt()))
                return settle("dispatch_rejected");
            long runId = pending.path("run_id").asLong();
            if (runId == 0) {
                String title = "bdi-" + pending.path("execution_id").asText();
                // A bounded search never treats absence as proof that dispatch was rejected.
                java.util.Set<Long> matches = new java.util.HashSet<>();
                for (int page = 1; page <= 5; page++) {
                    JsonNode runs = get("/repos/" + repository + "/actions/workflows/" + pending.path("workflow_file").asText()
                        + "/runs?event=workflow_dispatch&per_page=100&page=" + page).path("workflow_runs");
                    for (JsonNode run : runs) if (title.equals(run.path("display_title").asText())) matches.add(run.path("id").asLong());
                    if (runs.size() < 100) break;
                }
                if (matches.size() != 1 || matches.contains(0L)) return unresolved();
                runId = matches.iterator().next(); pending.put("run_id", runId); savePending();
            }
            journal.event("execution_reconciled", null, Map.of("execution_id", pending.path("execution_id").asText(), "github_run_id", runId));
            return settle(awaitSelectedJob(runId, pending.path("expected_job").asText(), Instant.now().plus(maxWait)));
        } catch (Exception error) {
            journal.event("reconciliation_unavailable", null, Map.of("reason", String.valueOf(error.getMessage())));
            return unresolved();
        }
    }

    private Result unresolved() {
        if (pending == null) return new Result("unknown", 0, "unresolved", 0, "");
        return result("unknown");
    }

    /** Explicit migration for old journals proving an HTTP rejection or local invalid header. No network requests. */
    Result reconcileRejectedDispatch(Path evidenceDirectory, Path archiveDirectory) throws Exception {
        if (pending == null || pending.path("run_id").asLong() != 0)
            throw new IllegalStateException("Requires a pending dispatch with no acknowledged run");
        var receipt = JSON.readTree(Files.readString(evidenceDirectory.resolve("controller-result.json")));
        if (!repository.equals(pending.path("repository").asText())
                || !apiBase.toString().equals(pending.path("api_base").asText())
                || !repository.equals(receipt.path("repository").asText())
                || !pending.path("campaign_id").asText().equals(receipt.path("campaign_id").asText())
                || !pending.path("release_sha").asText().equals(receipt.path("release_sha").asText())
                || !"github".equals(receipt.path("mode").asText()))
            throw new IllegalStateException("Rejection evidence does not match pending campaign/repository/release");
        byte[] evidence = Files.readAllBytes(evidenceDirectory.resolve("controller-journal.jsonl"));
        int intents = 0, rejections = 0;
        for (String line : new String(evidence, java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            // The historical logger failed to escape control characters. Keep original bytes
            // for the archive; permit them only while reading this explicit migration evidence.
            var event = JSON.reader().with(com.fasterxml.jackson.core.json.JsonReadFeature
                .ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature()).readTree(line);
            if (!pending.path("execution_id").asText().equals(event.path("execution_id").asText())) continue;
            switch (event.path("event").asText()) {
                case "dispatch_intent" -> {
                    for (String field : new String[]{"campaign_id", "entity", "attempt", "release_sha"})
                        if (!pending.path(field).asText().equals(event.path(field).asText()))
                            throw new IllegalStateException("Dispatch intent identity mismatch: " + field);
                    intents++;
                }
                case "execution_uncertain" -> {
                    String reason = event.path("reason").asText();
                    var match = java.util.regex.Pattern.compile("^GitHub dispatch returned HTTP (401|403|404|422): ").matcher(reason);
                    if (intents != 1 || !(match.find() || invalidAuthorizationHeader(reason)))
                        throw new IllegalStateException("Evidence does not prove an explicit dispatch rejection");
                    rejections++;
                }
                case "dispatch_acknowledged", "execution_reconciled", "execution_terminal" ->
                    throw new IllegalStateException("Evidence contains an acknowledged or settled execution");
                default -> { }
            }
        }
        if (intents != 1 || rejections != 1)
            throw new IllegalStateException("Requires exactly one matching dispatch intent and explicit rejection");
        Files.createDirectories(archiveDirectory);
        Files.write(archiveDirectory.resolve("rejected-dispatch-journal.jsonl"), evidence, java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.writeString(archiveDirectory.resolve("rejected-dispatch-pending.json"), pending.toString(), java.nio.file.StandardOpenOption.CREATE_NEW);
        journal.event("dispatch_rejection_recovered", null, Map.of("execution_id", pending.path("execution_id").asText(),
            "evidence_directory", evidenceDirectory.toAbsolutePath().toString()));
        return settle("dispatch_rejected");
    }

    // HttpRequest rejects this header locally, before client.send can transmit a POST.
    private static boolean invalidAuthorizationHeader(String reason) {
        String prefix = "invalid header value: \"Bearer ";
        if (!reason.startsWith(prefix) || !reason.endsWith("\"")) return false;
        String value = reason.substring(prefix.length(), reason.length() - 1);
        return value.chars().anyMatch(c -> (c < 32 && c != 9) || c == 127);
    }

    private Result result(String status) {
        long runId = pending.path("run_id").asLong();
        return new Result(status, Duration.between(Instant.parse(pending.path("started").asText()), Instant.now()).toMillis(),
            pending.path("execution_id").asText(), runId, runId == 0 ? "" : "https://github.com/" + repository + "/actions/runs/" + runId);
    }

    private Result settle(String status) throws IOException {
        Result result = result(status);
        if (!status.equals("unknown")) {
            journal.event("execution_terminal", null, Map.of("execution_id", result.executionId(), "github_run_id", result.githubRunId(), "status", status));
            if (stateFile != null) Files.deleteIfExists(stateFile);
            pending = null;
        }
        return result;
    }

    private long dispatch(String entity, int attempt, String executionId, String failureMode,
                          String experimentMode, String selectedSha) throws Exception {
        URI uri = endpoint("/repos/" + repository + "/actions/workflows/" + project.workflowFile() + "/dispatches");
        var inputs = JSON.createObjectNode();
        operationInputs.forEach(inputs::put);
        inputs.put("entity", entity).put("campaign_id", campaignId).put("execution_id", executionId)
            .put("attempt", String.valueOf(attempt)).put("release_sha", selectedSha)
            .put("failure_mode", failureMode).put("experiment_mode", experimentMode);
        var body = JSON.createObjectNode().put("ref", ref).set("inputs", inputs);
        HttpResponse<String> response = client.send(request(uri)
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            if (rejectedStatus(response.statusCode())) throw new DispatchRejected(response.statusCode());
            throw new IOException("GitHub dispatch returned HTTP " + response.statusCode() + ": " + response.body());
        }
        long runId = JSON.readTree(response.body()).path("workflow_run_id").asLong(0);
        if (runId <= 0) throw new IOException("Dispatch response did not contain workflow_run_id");
        return runId;
    }

    private static boolean rejectedStatus(int status) {
        return status == 401 || status == 403 || status == 404 || status == 422;
    }

    public Result executeOperation(String entity, int attempt, Map<String,String> inputs, Duration budget) throws Exception {
        operationInputs=Map.copyOf(inputs);
        operationDeadline=Instant.now().plus(budget);
        try { return execute(entity,attempt); }
        finally { operationInputs=Map.of(); operationDeadline=null; }
    }

    public JsonNode operationEvidence(Result result) throws Exception {
        Path directory=Path.of(required("BDI_RUN_DIR"),"operation-"+result.executionId());
        Files.createDirectories(directory);
        ProcessBuilder builder=project.project().equals("gotify")
            ? new ProcessBuilder("python3", Path.of(required("GOTIFY_REPO_ROOT"), "experiment/artifacts.py").toString(),
                "repair-"+result.executionId(), directory.toString())
            : new ProcessBuilder("gh","run","download",Long.toString(result.githubRunId()),
                "--repo",repository,"--name","repair-"+result.executionId(),"--dir",directory.toString());
        builder.environment().put("GH_TOKEN",token);
        builder.environment().put("GITHUB_TOKEN",token);
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("download.log").toFile());
        Process process=builder.start();
        if (!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Repair evidence download timeout"); }
        if (process.exitValue()!=0) throw new IOException("Repair evidence unavailable; see operation download log");
        return JSON.readTree(Files.readString(directory.resolve("receipt.json")));
    }

    private static final class DispatchRejected extends IOException {
        final int status;
        DispatchRejected(int status) {
            super("GitHub rejected workflow dispatch with HTTP " + status);
            this.status = status;
        }
    }

    private String awaitSelectedJob(long runId, String expectedJob, Instant deadline) throws Exception {
        while (Instant.now().isBefore(deadline)) {
            JsonNode run = get("/repos/" + repository + "/actions/runs/" + runId);
            if ("completed".equals(run.path("status").asText())) {
                JsonNode jobs = get("/repos/" + repository + "/actions/runs/" + runId + "/jobs?filter=latest&per_page=100").path("jobs");
                for (JsonNode job : jobs) {
                    if (expectedJob.equals(job.path("name").asText())) {
                        if (!"completed".equals(job.path("status").asText())) throw new IOException("Selected job is not terminal");
                        String status = normalize(job.path("conclusion").asText());
                        // Only a dedicated failed worker step proves this controlled transient fault.
                        // Arbitrary compiler/test/security failures remain deterministic failures.
                        if (status.equals("failure")) for (JsonNode step : job.path("steps")) {
                            if ("Controlled transient failure".equals(step.path("name").asText())
                                    && "failure".equals(step.path("conclusion").asText())) return "transient_failure";
                        }
                        return status;
                    }
                }
                throw new IOException("Selected job was absent or skipped: " + expectedJob);
            }
            Thread.sleep(pollInterval.toMillis());
        }
        // The remote job could still be deploying. Do not permit retry/rollback over it.
        return "unknown";
    }

    static String sourceFor(String entity, String candidate, String knownGood, ControllerProjectConfig project) {
        String selected = "known_good".equals(project.releaseSources().get(entity)) ? knownGood : candidate;
        if (selected == null || !selected.matches("(?i)[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Entity requires a validated immutable source: " + entity);
        }
        return selected;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = client.send(request(endpoint(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("GitHub API returned HTTP " + response.statusCode());
        return JSON.readTree(response.body());
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + token)
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("Content-Type", "application/json");
    }

    private URI endpoint(String path) { return URI.create(apiBase.toString().replaceAll("/$", "") + path); }
    private static String normalize(String value) {
        return switch (value) { case "success" -> "success"; case "cancelled" -> "cancelled";
            case "timed_out" -> "timeout"; case "skipped" -> "skipped"; case "failure", "action_required", "startup_failure", "stale" -> "failure"; default -> "unknown"; };
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
    private static String value(String name, String fallback) {
        String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value;
    }
    private static long number(String name, long fallback) {
        try { return Long.parseLong(value(name, Long.toString(fallback))); }
        catch (NumberFormatException error) { throw new IllegalStateException(name + " must be numeric"); }
    }
}
