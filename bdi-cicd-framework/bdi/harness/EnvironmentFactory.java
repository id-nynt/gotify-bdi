package harness;

import telemetry.TelemetryAdapter;

import java.net.URI;
import java.time.Duration;

/** Explicit infrastructure wiring helper; it contains no workflow policy. */
public final class EnvironmentFactory {
    private EnvironmentFactory() { }

    public static ObservationEnvironment create(String entity, URI healthUri, URI metricsUri,
                                                 URI dispatchUri, String token, String ref) {
        TelemetryAdapter telemetry = new TelemetryAdapter(
            entity, healthUri, metricsUri, Duration.ofSeconds(5), Duration.ofSeconds(15));
        CompositeObservationProvider observations = new CompositeObservationProvider(
            new TelemetryObservationProvider(telemetry));
        String marker = "/repos/";
        String path = dispatchUri.getPath();
        int repoStart = path.indexOf(marker) + marker.length();
        int workflowStart = path.indexOf("/actions/workflows/", repoStart);
        if (repoStart < marker.length() || workflowStart < 0 || !path.endsWith("/dispatches")) {
            throw new IllegalArgumentException("dispatchUri must be a GitHub workflow dispatch endpoint");
        }
        String repository = path.substring(repoStart, workflowStart);
        String workflowFile = path.substring(workflowStart + "/actions/workflows/".length(),
            path.length() - "/dispatches".length());
        URI apiBase = URI.create(dispatchUri.getScheme() + "://" + dispatchUri.getAuthority());
        GitHubActionsWorkflowExecutor executor = new GitHubActionsWorkflowExecutor(
            new GitHubActionsWorkflowExecutor.Config(apiBase, repository, workflowFile, token, ref,
                Duration.ofSeconds(5), Duration.ofMinutes(15)), observations::publish);
        return new ObservationEnvironment(
            executor,
            observations,
            new JasonBeliefAdapter());
    }
}
