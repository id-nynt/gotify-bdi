package harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GotifyProbeTelemetryTest {
    private final Instant now = Instant.parse("2026-09-24T08:00:00Z");
    private ObjectNode receipt() {
        var root = new ObjectMapper().createObjectNode().put("trial", "trial-1").put("release", "v2")
            .put("finished_at", now.toString());
        root.putObject("identity").put("execution_id", "execution-1");
        root.putObject("observation").put("data_status", "fresh").put("health", "healthy")
            .put("error_rate", 0).put("latency_p95_ms", 125).put("availability", 1);
        return root;
    }
    private ProjectTelemetryProvider.Measurement decode(ObjectNode value) {
        return GotifyProbeTelemetry.decode(value, "trial-1", "v2", "execution-1", 30, now);
    }
    @Test void actualHealthyProbeBecomesReadyMeasurement() {
        var sample = decode(receipt());
        assertEquals("fresh", sample.dataStatus());
        assertEquals("ready", sample.readiness());
        assertEquals(125, sample.latencyP95Ms());
    }
    @Test void staleAndMismatchedEvidenceCannotLookHealthy() {
        var stale = receipt().put("finished_at", now.minusSeconds(31).toString());
        assertEquals("unavailable", decode(stale).dataStatus());
        assertEquals("unavailable", decode(receipt().put("trial", "another-trial")).dataStatus());
        assertEquals("unavailable", decode(receipt().put("release", "v1")).dataStatus());
        var wrongExecution = receipt();
        ((ObjectNode)wrongExecution.path("identity")).put("execution_id", "older-deployment");
        assertEquals("unavailable", decode(wrongExecution).dataStatus());
    }
    @Test void observedStoppedContainerIsFailureRatherThanMissingData() {
        var stopped = receipt();
        ((ObjectNode)stopped.path("observation")).put("health", "unhealthy").put("availability", 0);
        assertEquals("fresh", decode(stopped).dataStatus());
        assertEquals("not_ready", decode(stopped).readiness());
    }
}
