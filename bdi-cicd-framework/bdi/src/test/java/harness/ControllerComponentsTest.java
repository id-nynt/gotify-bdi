package harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControllerComponentsTest {
    @Test void oldCanonicalSchemaRequiresRegeneration() {
        var error = assertThrows(java.io.IOException.class,
            () -> WorkflowRuntime.unwrap(java.util.Map.of("schema_version", 1)));
        assertTrue(error.getMessage().contains("regenerate"));
    }

    @Test
    void onlyRecoveryUsesPinnedKnownGoodSource() throws Exception {
        var config = ControllerProjectConfig.load(Path.of("fixtures/controller-workflow.yaml"));
        String candidate = "a".repeat(40);
        String baseline = "b".repeat(40);
        assertEquals(candidate, GitHubEntityExecution.sourceFor("production", candidate, baseline, config));
        assertEquals(baseline, GitHubEntityExecution.sourceFor("rollback", candidate, baseline, config));
        assertThrows(IllegalArgumentException.class, () -> GitHubEntityExecution.sourceFor("rollback", candidate, "", config));
        assertThrows(IllegalArgumentException.class, () -> GitHubEntityExecution.sourceFor("rollback", candidate, "main", config));
    }

    @Test
    void stillRunningRemoteJobReturnsUnknownInsteadOfTriggeringRecovery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/dispatches", exchange ->
            respond(exchange, 200, "{\"workflow_run_id\":321}"));
        server.createContext("/repos/example/repository/actions/runs/321", exchange ->
            respond(exchange, 200, "{\"status\":\"in_progress\"}"));
        server.start();
        try {
            var config = ControllerProjectConfig.load(Path.of("fixtures/controller-workflow.yaml"));
            var adapter = new GitHubEntityExecution(config, new StructuredEventLogger(null),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "example/repository", "test-token",
                "main", "a".repeat(40), "timeout-test", Duration.ofMillis(1), Duration.ofMillis(100));
            assertEquals("unknown", adapter.execute("production", 1).status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void operatorCanChangeFaultsBetweenDispatches() throws Exception {
        Path file = Files.createTempFile("controller-injection", ".properties");
        try {
            var plan = new ExperimentExecutionPlan(file.toString());
            assertEquals("none", plan.next("build").failureMode());
            Files.writeString(file, "test.1.failure_mode=force_failure\n");
            assertEquals("force_failure", plan.next("test").failureMode());
            assertEquals("none", plan.next("test").failureMode());
            Files.writeString(file, "staging.force_error_rate=1\n");
            assertEquals("1", plan.next("staging").forceErrorRate());
            Files.writeString(file, "");
            assertEquals("0", plan.next("staging").forceErrorRate());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void paymentExecutionMappingIsConfigurable() throws Exception {
        ControllerProjectConfig config = ControllerProjectConfig.load(Path.of("fixtures/controller-workflow.yaml"));
        assertEquals("entity-execution.yml", config.workflowFile());
        assertEquals("Build entity", config.jobNames().get("build"));
        assertEquals("staging", config.environments().get("staging"));
        assertEquals(36, config.observationAttempts());
    }

    @Test
    void secondProjectHasDifferentEntitiesWithoutRuntimeCodeChanges() throws Exception {
        ControllerProjectConfig config = ControllerProjectConfig.load(Path.of("fixtures/reporting-workflow.yaml"));
        assertEquals(java.util.Set.of("package", "verify", "preview"), config.jobNames().keySet());
        assertTrue(config.environments().isEmpty());
    }

    @Test
    void scenarioExecutorSupportsRetryAndExhaustionInputs() throws Exception {
        var transientFailure = new ScenarioEntityExecution("transient_test_failure");
        assertEquals("transient_failure", transientFailure.execute("test", 1).status());
        assertEquals("success", transientFailure.execute("test", 2).status());
        var persistentFailure = new ScenarioEntityExecution("exhausted_test_failure");
        assertEquals("transient_failure", persistentFailure.execute("test", 1).status());
        assertEquals("transient_failure", persistentFailure.execute("test", 2).status());
    }

    @Test
    void githubAdapterCorrelatesDispatchWithTheExactSelectedJob() throws Exception {
        AtomicReference<String> dispatchBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/dispatches", exchange -> {
            dispatchBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"workflow_run_id\":321}");
        });
        server.createContext("/repos/example/repository/actions/runs/321/jobs", exchange ->
            respond(exchange, 200, "{\"jobs\":[{\"name\":\"Build entity\",\"status\":\"completed\",\"conclusion\":\"success\"}]}"));
        server.createContext("/repos/example/repository/actions/runs/321", exchange ->
            respond(exchange, 200, "{\"status\":\"completed\"}"));
        server.start();
        try {
            ControllerProjectConfig config = ControllerProjectConfig.load(Path.of("fixtures/controller-workflow.yaml"));
            Path journalFile = Files.createTempFile("controller-adapter", ".jsonl");
            var adapter = new GitHubEntityExecution(config, new StructuredEventLogger(journalFile),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "example/repository", "test-token",
                "experiment-v2", "0123456789abcdef0123456789abcdef01234567", "campaign-test",
                Duration.ofMillis(1), Duration.ofSeconds(2));
            EntityExecution.Result result = adapter.execute("build", 1);
            assertEquals("success", result.status());
            assertEquals(321, result.githubRunId());
            assertTrue(dispatchBody.get().contains("\"entity\":\"build\""));
            assertTrue(dispatchBody.get().contains("\"release_sha\":\"0123456789abcdef0123456789abcdef01234567\""));
            assertTrue(Files.readString(journalFile).contains("dispatch_acknowledged"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void githubAdapterRejectsACompletedRunWithoutTheSelectedJob() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/dispatches", exchange ->
            respond(exchange, 200, "{\"workflow_run_id\":654}"));
        server.createContext("/repos/example/repository/actions/runs/654/jobs", exchange ->
            respond(exchange, 200, "{\"jobs\":[{\"name\":\"Unrelated job\",\"status\":\"completed\",\"conclusion\":\"success\"}]}"));
        server.createContext("/repos/example/repository/actions/runs/654", exchange ->
            respond(exchange, 200, "{\"status\":\"completed\"}"));
        server.start();
        try {
            ControllerProjectConfig config = ControllerProjectConfig.load(Path.of("fixtures/controller-workflow.yaml"));
            var adapter = new GitHubEntityExecution(config, new StructuredEventLogger(null),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "example/repository", "test-token",
                "experiment-v2", "0123456789abcdef0123456789abcdef01234567", "campaign-test",
                Duration.ofMillis(1), Duration.ofSeconds(2));
            assertEquals("unknown", adapter.execute("build", 1).status());
            assertEquals("unknown", adapter.reconcile("build", 1).status());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
