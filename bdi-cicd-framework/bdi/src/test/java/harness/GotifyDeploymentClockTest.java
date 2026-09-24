package harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GotifyDeploymentClockTest {
    private final Instant now = Instant.parse("2026-09-24T09:00:00Z");
    private ObjectNode clock() {
        return new ObjectMapper().createObjectNode().put("trial", "trial").put("approach", "bdi")
            .put("execution_id", "execution").put("release", "v2")
            .put("ready_at_unix", now.minusSeconds(25).getEpochSecond());
    }
    @Test void githubCompletionDelayConsumesSameRecoveryBudget() {
        assertEquals(25000, ControllerEnvironment.deploymentElapsed(clock(), "trial", "execution", now));
    }
    @Test void mismatchedOrFutureClockIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ControllerEnvironment.deploymentElapsed(clock(), "other", "execution", now));
        assertThrows(IllegalArgumentException.class, () -> ControllerEnvironment.deploymentElapsed(
            clock().put("ready_at_unix", now.plusSeconds(10).getEpochSecond()), "trial", "execution", now));
    }
}
