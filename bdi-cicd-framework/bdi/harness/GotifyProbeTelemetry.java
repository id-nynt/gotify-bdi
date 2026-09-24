package harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Transport one shared Gotify observation into measurements, never select an action. */
public final class GotifyProbeTelemetry {
    private static final ObjectMapper JSON = new ObjectMapper();
    private GotifyProbeTelemetry() { }

    public static ProjectTelemetryProvider.Measurement measure(List<String> template, String entity,
                                                               String executionId, int maxAgeSeconds) {
        var unknown = new ProjectTelemetryProvider.Measurement("unavailable", "unknown", 0, 0, 0);
        try {
            String root = required("GOTIFY_REPO_ROOT"), trial = required("BDI_CAMPAIGN_ID");
            String release = entity.equals("rollback") ? "v1" : "v2";
            List<String> command = template.stream().map(s -> s.replace("{{repo_root}}", root)
                .replace("{{trial_id}}", trial).replace("{{release}}", release)
                .replace("{{execution_id}}", executionId)).toList();
            Path directory = Path.of(required("BDI_RUN_DIR"), "probe-observations");
            Files.createDirectories(directory);
            String id = UUID.randomUUID().toString();
            Path output = directory.resolve(id + ".json");
            Process process = new ProcessBuilder(command).redirectOutput(output.toFile())
                .redirectError(directory.resolve(id + ".stderr").toFile()).start();
            // Match the conventional operation wrapper; the shared operation itself
            // enforces its 180-second command guard and records uncertain completion.
            // The agent's 45-second observation window still rejects late samples.
            if (!process.waitFor(240, TimeUnit.SECONDS)) { process.destroyForcibly(); return unknown; }
            var receipt = JSON.readTree(Files.readString(output));
            return decode(receipt, trial, release, executionId, maxAgeSeconds, Instant.now());
        } catch (Exception error) { return unknown; }
    }

    static ProjectTelemetryProvider.Measurement decode(com.fasterxml.jackson.databind.JsonNode receipt,
            String trial, String release, String executionId, int maxAgeSeconds, Instant clock) {
        var unknown = new ProjectTelemetryProvider.Measurement("unavailable", "unknown", 0, 0, 0);
        try {
            if (!trial.equals(receipt.path("trial").asText()) || !release.equals(receipt.path("release").asText())) return unknown;
            var identity = receipt.path("identity");
            if (!executionId.equals(identity.path("execution_id").asText())) return unknown;
            Instant timestamp = Instant.parse(receipt.path("finished_at").asText());
            long age = Duration.between(timestamp, clock).toMillis();
            if (age < -1000 || age > maxAgeSeconds * 1000L) return unknown;
            var sample = receipt.path("observation");
            if (!sample.path("data_status").asText().equals("fresh")) return unknown;
            for (String key : List.of("error_rate", "latency_p95_ms", "availability"))
                if (!sample.path(key).isNumber() || !Double.isFinite(sample.path(key).asDouble())) return unknown;
            if (sample.path("error_rate").asDouble() < 0 || sample.path("error_rate").asDouble() > 1
                    || sample.path("latency_p95_ms").asDouble() < 0
                    || !List.of(0.0, 1.0).contains(sample.path("availability").asDouble())) return unknown;
            String readiness = sample.path("health").asText().equals("healthy") ? "ready" : "not_ready";
            return new ProjectTelemetryProvider.Measurement("fresh", readiness, sample.path("error_rate").asDouble(),
                sample.path("latency_p95_ms").asDouble(), sample.path("availability").asDouble());
        } catch (Exception error) { return unknown; }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}
