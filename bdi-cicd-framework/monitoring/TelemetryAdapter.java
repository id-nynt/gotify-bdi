package telemetry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the demo service's health and Prometheus-compatible OpenTelemetry
 * metrics into stable observations. No OpenTelemetry types cross this boundary.
 */
public final class TelemetryAdapter {
    private static final Pattern SAMPLE = Pattern.compile(
        "^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{([^}]*)})?\\s+([-+0-9.eE]+)(?:\\s+.*)?$");
    private static final Pattern JSON_STATUS = Pattern.compile("\\\"status\\\"\\s*:\\s*\\\"([^\"]+)\\\"");

    private final String entity;
    private final URI healthUri;
    private final URI metricsUri;
    private final HttpClient client;
    private final Duration staleAfter;
    private Instant lastSuccessfulTelemetry;

    public TelemetryAdapter(String entity, URI healthUri, URI metricsUri,
                            Duration requestTimeout, Duration staleAfter) {
        this.entity = entity;
        this.healthUri = healthUri;
        this.metricsUri = metricsUri;
        this.client = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
        this.staleAfter = staleAfter;
    }

    public List<Observation> pollOnce() {
        Instant now = Instant.now();
        List<Observation> observations = new ArrayList<>();
        boolean healthAvailable = false;
        boolean metricsAvailable = false;

        try {
            HttpResponse<String> response = get(healthUri);
            String health = response.statusCode() == 200 ? extractHealth(response.body()) : "unhealthy";
            observations.add(new Observation(entity, "health", health, now));
            healthAvailable = true;
        } catch (Exception ignored) {
            observations.add(new Observation(entity, "health", "unknown", now));
        }

        try {
            HttpResponse<String> response = get(metricsUri);
            Map<String, Double> metrics = parseMetrics(response.body());
            addMetric(observations, metrics, "payment_error_rate", "error_rate", now);
            if (!metrics.containsKey("payment_error_rate")) {
                Double requests = metrics.get("payment_request_count_total");
                Double errors = metrics.get("payment_error_count_total");
                if (requests != null && errors != null && requests > 0) {
                    observations.add(new Observation(entity, "error_rate", errors / requests, now));
                }
            }
            Double count = metrics.get("payment_request_latency_ms_milliseconds_count");
            Double sum = metrics.get("payment_request_latency_ms_milliseconds_sum");
            if (count != null && sum != null && count > 0) {
                observations.add(new Observation(entity, "latency", sum / count, now));
            }
            metricsAvailable = true;
        } catch (Exception ignored) {
            // Availability and stale state below tell the consumer why values are absent.
        }

        if (healthAvailable || metricsAvailable) {
            lastSuccessfulTelemetry = now;
            observations.add(new Observation(entity, "data_status", "fresh", now));
        } else if (lastSuccessfulTelemetry == null
                || Duration.between(lastSuccessfulTelemetry, now).compareTo(staleAfter) > 0) {
            observations.add(new Observation(entity, "data_status", "stale", now));
        } else {
            observations.add(new Observation(entity, "data_status", "unavailable", now));
        }
        return observations;
    }

    public List<Observation> executionObservations(String status, long durationMs) {
        Instant now = Instant.now();
        return List.of(
            new Observation(entity, "execution_status", status, now),
            new Observation(entity, "duration", durationMs, now)
        );
    }

    private HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String extractHealth(String body) {
        Matcher matcher = JSON_STATUS.matcher(body);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    private void addMetric(List<Observation> observations, Map<String, Double> metrics,
                           String rawName, String property, Instant timestamp) {
        Double value = metrics.get(rawName);
        if (value != null) observations.add(new Observation(entity, property, value, timestamp));
    }

    private Map<String, Double> parseMetrics(String text) {
        Map<String, Double> result = new HashMap<>();
        for (String line : text.split("\\R")) {
            Matcher matcher = SAMPLE.matcher(line.trim());
            if (!matcher.matches()) continue;
            String name = matcher.group(1);
            String labels = matcher.group(2);
            if (labels != null && labels.contains("route=\"/pay\"")) {
                // Keep only payment-route samples for latency and request/error rates.
            } else if (name.startsWith("payment_")) {
                continue;
            }
            try {
                result.putIfAbsent(name, Double.parseDouble(matcher.group(3)));
                if (name.endsWith("_sum") || name.endsWith("_count")) {
                    result.put(name, Double.parseDouble(matcher.group(3)));
                }
            } catch (NumberFormatException ignored) {
                // A malformed metric is unavailable, not a zero.
            }
        }
        return result;
    }

    private static void printUsage() {
        System.out.println("Usage: java telemetry.TelemetryAdapter --entity NAME --health-url URL --metrics-url URL [--execution STATUS,DURATION_MS]");
    }

    public static void main(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            if (args[i].startsWith("--")) options.put(args[i].substring(2), args[i + 1]);
        }
        if (!options.containsKey("entity") || !options.containsKey("health-url") || !options.containsKey("metrics-url")) {
            printUsage();
            System.exit(2);
        }
        TelemetryAdapter adapter = new TelemetryAdapter(
            options.get("entity"), URI.create(options.get("health-url")), URI.create(options.get("metrics-url")),
            Duration.ofSeconds(5), Duration.ofSeconds(15));
        adapter.pollOnce().forEach(observation -> System.out.println(observation.toJson()));
        String execution = options.get("execution");
        if (execution != null) {
            String[] parts = execution.split(",", 2);
            adapter.executionObservations(parts[0], Long.parseLong(parts[1]))
                .forEach(observation -> System.out.println(observation.toJson()));
        }
    }
}
