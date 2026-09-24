package harness;

import java.nio.file.Path;

/** Jason environment for observing one already-started GitHub Actions run. */
public final class ProjectRunEnvironment extends ObservationEnvironment {
    public ProjectRunEnvironment() {
        this(components());
    }

    private record Components(ObservedRunExecutor executor, CompositeObservationProvider observations,
                              CorrelationRegistry registry, StructuredEventLogger logger) { }

    private ProjectRunEnvironment(Components components) {
        super(components.executor(), components.observations(), new JasonBeliefAdapter(),
            components.registry(), components.logger());
    }

    private static Components components() {
        try {
            ProjectConfig project = ProjectConfig.load(Path.of(value("BDI_PROJECT_FILE", "../models/payment_project.yaml")));
            String target = value("BDI_TARGET_ENV", project.promotionGate().observe());
            ProjectTelemetryProvider telemetry = new ProjectTelemetryProvider(project, target,
                project.promotionGate().observe());
            String fixturePath = System.getenv("BDI_GITHUB_JOBS_FIXTURE");
            Path fixture = fixturePath == null || fixturePath.isBlank() ? null : Path.of(fixturePath);
            String runText = value("BDI_GITHUB_RUN_ID", value("GITHUB_RUN_ID", "0"));
            if (fixture == null && runText.equals("0")) {
                throw new IllegalStateException("BDI_GITHUB_RUN_ID or BDI_GITHUB_JOBS_FIXTURE is required");
            }
            GitHubRunObserver github = new GitHubRunObserver(project, System.getenv("GITHUB_REPOSITORY"),
                Long.parseLong(runText), System.getenv("GITHUB_TOKEN"), fixture);
            CorrelationRegistry registry = new CorrelationRegistry();
            StructuredEventLogger logger = new StructuredEventLogger(Path.of("build/runtime.jsonl"));
            CompositeObservationProvider observations = new CompositeObservationProvider(telemetry, registry, logger);
            ObservedRunExecutor executor = new ObservedRunExecutor(github, telemetry,
                project.promotionGate().observe(), observations::publish);
            return new Components(executor, observations, registry, logger);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot start project run observer: " + error.getMessage(), error);
        }
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
