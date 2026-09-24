package harness;

import cicd.observer.PrometheusTelemetryObserver;
import cicd.observer.TelemetrySample;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import telemetry.Observation;

/** Converts one project's URLs and thresholds into stable observations. */
public final class ProjectTelemetryProvider implements ObservationProvider {
    public record Assessment(String decision, String reason, Double errorRate,
                             Double latencyP95Ms, String readiness) { }

    private final ProjectConfig project;
    private final String environment;
    private final String entity;
    private final ProjectConfig.Environment endpoints;
    private final HttpClient client = HttpClient.newHttpClient();
    private final PrometheusTelemetryObserver prometheus;
    private final String runId;

    public ProjectTelemetryProvider(ProjectConfig project, String environment) {
        this(project, environment, environment);
    }

    public ProjectTelemetryProvider(ProjectConfig project, String environment, String entity) {
        this(project, environment, entity, System.getenv().getOrDefault("BDI_TELEMETRY_RUN_ID", "local"));
    }

    public ProjectTelemetryProvider(ProjectConfig project, String environment, String entity, String runId) {
        this.project = project;
        this.runId = runId;
        this.environment = environment;
        this.entity = entity;
        this.endpoints = project.environment(environment);
        if (!runId.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid BDI_TELEMETRY_RUN_ID");
        }
        this.prometheus = !endpoints.probeCommand().isEmpty() ? null : new PrometheusTelemetryObserver(client, endpoints.prometheusUrl().toString(),
            metricQuery(project, "error_rate_query", runId), metricQuery(project, "latency_p95_ms_query", runId),
            metricQuery(project, "availability_query", runId), project.maxAgeSeconds(), project.metrics().containsKey("sample_age_seconds_query")
                ? metricQuery(project, "sample_age_seconds_query", runId) : null);
    }

    static String metricQuery(ProjectConfig project, String name, String runId) {
        return project.metrics().get(name).replace("{{run_id}}", runId);
    }

    public record Measurement(String dataStatus, String readiness, double errorRate, double latencyP95Ms, double availability) { }

    /** Payment-worker repair requires a live identity check as well as correlated metrics. */
    public Measurement measureRepair() {
        if (!endpoints.probeCommand().isEmpty()) return measure();
        try {
            var request=HttpRequest.newBuilder(endpoints.readyUrl().resolve("/health")).timeout(Duration.ofSeconds(5)).GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200 || !runId.equals(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response.body()).path("deploymentRunId").asText()))
                return new Measurement("unavailable","unknown",0,0,0);
        } catch (Exception error) { return new Measurement("unavailable","unknown",0,0,0); }
        return measure();
    }

    /** Raw normalized measurements. Policy is evaluated by the controller agent. */
    public Measurement measure() {
        if (!endpoints.probeCommand().isEmpty())
            return GotifyProbeTelemetry.measure(endpoints.probeCommand(), entity, runId, project.maxAgeSeconds());
        String readiness = ready();
        if (readiness.equals("not_ready")) return new Measurement("fresh", readiness, 0, 0, 0);
        if (readiness.equals("unknown")) return new Measurement("unavailable", readiness, 0, 0, 0);
        try {
            TelemetrySample sample = prometheus.observe(environment);
            return new Measurement("fresh", readiness, sample.errorRate(), sample.latencyP95Ms(), sample.availability());
        } catch (Exception error) {
            return new Measurement("unavailable", readiness, 0, 0, 0);
        }
    }

    public Assessment assess() {
        if (!endpoints.probeCommand().isEmpty()) {
            var sample = measure();
            if (!sample.dataStatus().equals("fresh")) return new Assessment("unknown", "probe_unavailable", null, null, sample.readiness());
            boolean healthy = sample.readiness().equals("ready") && sample.availability() == 1
                && sample.errorRate() <= project.maxErrorRate() && sample.latencyP95Ms() <= project.maxLatencyP95Ms();
            return new Assessment(healthy ? "allow" : "block", "gotify_probe", sample.errorRate(), sample.latencyP95Ms(), sample.readiness());
        }
        String readiness = ready();
        if (readiness.equals("not_ready")) {
            return new Assessment("block", "readiness_not_ready", null, null, readiness);
        }
        if (readiness.equals("unknown")) {
            return new Assessment("unknown", "readiness_unknown", null, null, readiness);
        }
        TelemetrySample sample;
        try {
            sample = prometheus.observe(environment);
        } catch (Exception ignored) {
            return new Assessment("unknown", "metrics_unavailable", null, null, readiness);
        }
        if (sample.availability() < 1) {
            return new Assessment("block", "service_unready_metric", sample.errorRate(), sample.latencyP95Ms(), readiness);
        }
        if (sample.errorRate() > project.maxErrorRate()) {
            return new Assessment("block", "high_http_error_rate", sample.errorRate(), sample.latencyP95Ms(), readiness);
        }
        if (sample.latencyP95Ms() > project.maxLatencyP95Ms()) {
            return new Assessment("block", "high_http_latency", sample.errorRate(), sample.latencyP95Ms(), readiness);
        }
        return new Assessment("allow", "healthy", sample.errorRate(), sample.latencyP95Ms(), readiness);
    }

    @Override
    public List<Observation> getObservations() {
        Assessment assessment = assess();
        Instant now = Instant.now();
        List<Observation> items = new ArrayList<>();
        items.add(new Observation(entity, "gate", assessment.decision(), now));
        items.add(new Observation(entity, "health", assessment.decision().equals("allow") ? "healthy"
            : assessment.decision().equals("block") ? "unhealthy" : "unknown", now));
        items.add(new Observation(entity, "readiness", assessment.readiness(), now));
        items.add(new Observation(entity, "data_status", assessment.errorRate() == null ? "unavailable" : "fresh", now));
        if (assessment.errorRate() != null) {
            items.add(new Observation(entity, "error_rate", assessment.errorRate(), now));
            items.add(new Observation(entity, "latency", assessment.latencyP95Ms(), now));
        }
        return items;
    }

    private String ready() {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoints.readyUrl())
                .timeout(Duration.ofSeconds(5)).GET().build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 200) return "ready";
            if (status == 503) return "not_ready";
            return "unknown";
        } catch (Exception ignored) {
            return "unknown";
        }
    }
}
