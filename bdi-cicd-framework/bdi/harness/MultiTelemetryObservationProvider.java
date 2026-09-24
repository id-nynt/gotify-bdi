package harness;

import telemetry.Observation;
import telemetry.TelemetryAdapter;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Polls the configured reachable deployment instances without changing the generic provider contract. */
public final class MultiTelemetryObservationProvider implements ObservationProvider {
    private final Map<String, TelemetryAdapter> adapters;

    public MultiTelemetryObservationProvider(Map<String, TelemetryAdapter> adapters) {
        this.adapters = Map.copyOf(adapters);
    }

    @Override
    public List<Observation> getObservations() {
        List<Observation> observations = new ArrayList<>();
        for (TelemetryAdapter adapter : adapters.values()) observations.addAll(adapter.pollOnce());
        return observations;
    }

    public static MultiTelemetryObservationProvider fromEnvironment() {
        java.util.LinkedHashMap<String, TelemetryAdapter> adapters = new java.util.LinkedHashMap<>();
        addIfConfigured(adapters, "staging", "STAGING_HEALTH_URL", "STAGING_METRICS_URL");
        addIfConfigured(adapters, "production", "PRODUCTION_HEALTH_URL", "PRODUCTION_METRICS_URL");
        return new MultiTelemetryObservationProvider(adapters);
    }

    private static void addIfConfigured(Map<String, TelemetryAdapter> result, String entity,
                                        String healthName, String metricsName) {
        String health = System.getenv(healthName);
        String metrics = System.getenv(metricsName);
        if (health == null || metrics == null || health.isBlank() || metrics.isBlank()) return;
        result.put(entity, new TelemetryAdapter(entity, URI.create(health), URI.create(metrics),
            java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(15)));
    }
}
