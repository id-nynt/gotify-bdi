package harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads an existing GitHub Actions run; it never dispatches or mutates one. */
public final class GitHubRunObserver {
    public record Job(String role, String status, String conclusion, long durationMs) {
        public boolean completed() { return status.equals("completed"); }
        public boolean succeeded() { return completed() && conclusion.equals("success"); }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProjectConfig project;
    private final String repository;
    private final long runId;
    private final String token;
    private final Path fixture;
    private final HttpClient client = HttpClient.newHttpClient();

    public GitHubRunObserver(ProjectConfig project, String repository, long runId, String token, Path fixture) {
        this.project = project;
        this.repository = repository;
        this.runId = runId;
        this.token = token;
        this.fixture = fixture;
    }

    public Map<String, Job> observe() throws IOException, InterruptedException {
        JsonNode root = fixture == null ? fetch() : JSON.readTree(Files.readString(fixture));
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) throw new IOException("GitHub response has no jobs array");
        Map<String, Job> result = new LinkedHashMap<>();
        for (var mapping : project.githubJobNames().entrySet()) {
            for (JsonNode item : jobs) {
                if (!mapping.getValue().equals(item.path("name").asText())) continue;
                String status = item.path("status").asText("unknown");
                String conclusion = item.path("conclusion").asText("unknown");
                long duration = duration(item);
                result.put(mapping.getKey(), new Job(mapping.getKey(), status, conclusion, duration));
                break;
            }
        }
        return result;
    }

    public Job awaitTerminal(String role, Duration timeout) throws IOException, InterruptedException {
        if (!project.jobs().containsKey(role)) throw new IllegalArgumentException("Unknown job role: " + role);
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Job job = observe().get(role);
            if (job != null && job.completed()) return job;
            if (fixture != null) throw new IOException("Fixture has no completed job for " + role);
            Thread.sleep(5000);
        }
        throw new IOException("Timed out waiting for GitHub job " + role + " in run " + runId);
    }

    private JsonNode fetch() throws IOException, InterruptedException {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || runId <= 0
            || token == null || token.isBlank()) {
            throw new IOException("Live GitHub observation requires repository, run ID, and Actions-read token");
        }
        URI uri = URI.create("https://api.github.com/repos/" + repository + "/actions/runs/" + runId
            + "/jobs?filter=latest&per_page=100");
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + token)
            .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("GitHub jobs returned HTTP " + response.statusCode());
        return JSON.readTree(response.body());
    }

    private static long duration(JsonNode item) {
        try {
            String start = item.path("started_at").asText("");
            String end = item.path("completed_at").asText("");
            return start.isEmpty() || end.isEmpty() ? 0 : Duration.between(Instant.parse(start), Instant.parse(end)).toMillis();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
