package harness;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import telemetry.Observation;

/** Read-only run adapter: run_job means inspect an existing job, never dispatch one. */
public final class ObservedRunExecutor implements WorkflowExecutor {
    private final GitHubRunObserver github;
    private final ProjectTelemetryProvider telemetry;
    private final String gateSource;
    private final Consumer<Observation> publish;

    public ObservedRunExecutor(GitHubRunObserver github, ProjectTelemetryProvider telemetry, String gateSource,
                               Consumer<Observation> publish) {
        this.github = github;
        this.telemetry = telemetry;
        this.gateSource = gateSource;
        this.publish = publish;
    }

    @Override
    public void runJob(String entity) throws Exception {
        GitHubRunObserver.Job job = github.awaitTerminal(entity, Duration.ofMinutes(15));
        if (entity.equals(gateSource) && job.succeeded()) {
            // Publish the gate before the staging execution result: Jason must
            // see the observation before considering the production plan.
            telemetry.getObservations().forEach(publish);
        }
        Instant now = Instant.now();
        publish.accept(new Observation(entity, "duration", job.durationMs(), now));
        publish.accept(new Observation(entity, "execution_status", job.succeeded() ? "success" : "fail", now));
    }
}
