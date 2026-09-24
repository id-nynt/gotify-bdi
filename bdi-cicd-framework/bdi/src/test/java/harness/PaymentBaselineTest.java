package harness;

import cicd.observer.PrometheusTelemetryObserver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentBaselineTest {
    private static final Path PROJECT = Path.of("../parser/fixtures/legacy/payment_project.yaml");

    @Test
    void configMapsRealWorkflowJobsAndMetricQueries() throws Exception {
        ProjectConfig config = ProjectConfig.load(PROJECT);
        assertEquals(List.of("build", "test", "security", "staging", "production"),
            List.copyOf(config.jobs().keySet()));
        assertEquals("Deploy staging", config.githubJobNames().get("staging"));
        assertEquals(new ProjectConfig.PromotionGate("production", "staging"), config.promotionGate());
        assertTrue(config.metrics().get("latency_p95_ms_query").contains("payment_http_request_duration"));
        assertTrue(ProjectTelemetryProvider.metricQuery(config, "error_rate_query", "12345-2")
            .contains("ci_run_id=\"12345-2\""));
        assertEquals(0.05, config.maxErrorRate());
    }

    @Test
    void fixtureMapsGitHubDisplayNamesToStableRoles() throws Exception {
        ProjectConfig config = ProjectConfig.load(PROJECT);
        var observer = new GitHubRunObserver(config, null, 0, null,
            Path.of("fixtures/payment-jobs-success.json"));
        var jobs = observer.observe();
        assertEquals(List.of("build", "test", "security", "staging", "production"),
            List.copyOf(jobs.keySet()));
        assertTrue(jobs.get("staging").succeeded());
        assertEquals(60_000, jobs.get("staging").durationMs());
    }

    @Test
    void missingOrNonfinitePrometheusDataFailsClosed() throws Exception {
        assertThrows(IOException.class, () -> PrometheusTelemetryObserver.firstValue(
            "{\"status\":\"success\",\"data\":{\"result\":[]}}"));
        assertThrows(IOException.class, () -> PrometheusTelemetryObserver.firstValue(
            "{\"status\":\"success\",\"data\":{\"result\":[{\"value\":[1,\"NaN\"]}]}}"));
        assertEquals(0.08, PrometheusTelemetryObserver.firstValue(
            "{\"status\": \"success\", \"data\":{\"result\":[{\"value\":[1,\"0.08\"]}]}}"));
    }

    @Test
    void gateEvidenceRequiresEveryPrerequisiteJobToSucceed() throws Exception {
        ProjectConfig config = ProjectConfig.load(PROJECT);
        var jobs = new LinkedHashMap<>(new GitHubRunObserver(config, null, 0, null,
            Path.of("fixtures/payment-jobs-pre-promotion.json")).observe());
        assertFalse(jobs.containsKey("production"));
        var healthy = new ProjectTelemetryProvider.Assessment("allow", "healthy", 0.0, 20.0, "ready");
        assertEquals("passed", GateEvidence.from(config, jobs, healthy).workflow());

        var staging = jobs.remove("staging");
        assertEquals("unknown", GateEvidence.from(config, jobs, healthy).workflow());
        jobs.put("staging", staging);
        assertEquals("passed", GateEvidence.from(config, jobs, healthy).workflow());

        jobs.remove("security");
        assertEquals("unknown", GateEvidence.from(config, jobs, healthy).workflow());
        jobs.put("security", new GitHubRunObserver.Job("security", "completed", "failure", 20_000));
        assertEquals("failed", GateEvidence.from(config, jobs, healthy).workflow());
    }
}
