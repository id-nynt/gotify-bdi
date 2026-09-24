package harness;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/** Production Jason environment wiring for the real GitHub/telemetry path. */
public final class GitHubEnvironment extends ObservationEnvironment {
    public GitHubEnvironment() {
        this(createComponents());
    }

    private GitHubEnvironment(Components components) {
        super(components.executor(), components.provider(), new JasonBeliefAdapter(),
            components.registry(), components.logger());
    }

    private record Components(GitHubActionsWorkflowExecutor executor,
                              CompositeObservationProvider provider,
                              CorrelationRegistry registry,
                              StructuredEventLogger logger) { }

    private static Components createComponents() {
        String token = required("GITHUB_TOKEN");
        String repository = required("GITHUB_REPOSITORY");
        String ref = value("GITHUB_REF", "main");
        URI apiBase = URI.create(value("GITHUB_API_URL", "https://api.github.com"));
        String workflowFile = value("GITHUB_WORKFLOW_FILE", "entity-execution.yml");
        CorrelationRegistry registry = new CorrelationRegistry();
        StructuredEventLogger logger = new StructuredEventLogger(Path.of(
            value("BDI_LOG_FILE", "java-jason/runtime-artifacts/runtime.jsonl")));
        CompositeObservationProvider provider = new CompositeObservationProvider(
            MultiTelemetryObservationProvider.fromEnvironment(), registry, logger);
        GitHubActionsWorkflowExecutor executor = new GitHubActionsWorkflowExecutor(
            new GitHubActionsWorkflowExecutor.Config(apiBase, repository, workflowFile, token, ref,
                Duration.ofSeconds(5), Duration.ofMinutes(15)), provider::publish, registry, logger);
        logger.event("integration_started", null, java.util.Map.of(
            "experiment_id", value("BDI_EXPERIMENT_ID", "experiment-local"),
            "release_id", value("BDI_RELEASE_ID", ref),
            "repository", repository, "workflow_file", workflowFile));
        return new Components(executor, provider, registry, logger);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
