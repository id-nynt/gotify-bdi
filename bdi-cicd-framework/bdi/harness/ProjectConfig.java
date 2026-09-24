package harness;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Small per-application contract. Core observers do not know payment job names or ports. */
public record ProjectConfig(String project, Map<String, String> jobs,
                            Map<String, String> githubJobNames,
                            Map<String, Environment> environments,
                            Map<String, String> metrics,
                            PromotionGate promotionGate,
                            double maxErrorRate, double maxLatencyP95Ms, int maxAgeSeconds) {
    public record Environment(URI readyUrl, URI prometheusUrl, java.util.List<String> probeCommand) {
        public Environment(URI readyUrl, URI prometheusUrl) { this(readyUrl, prometheusUrl, java.util.List.of()); }
    }
    public record PromotionGate(String before, String observe) { }

    public static ProjectConfig load(Path path) throws IOException {
        Object parsed;
        try (var input = Files.newInputStream(path)) {
            parsed = new Yaml().load(input);
        }
        boolean canonical = parsed instanceof Map<?, ?> document && document.containsKey("schema_version");
        if (canonical) parsed = WorkflowRuntime.unwrap(parsed);
        if (!(parsed instanceof Map<?, ?> root)) throw new IOException("Project config must be a YAML mapping");
        String name = requiredString(root, "project");
        Map<String, String> jobs = canonical ? Map.of() : strings(root.get("jobs"), "jobs");
        Map<String, String> names = canonical ? Map.of() : strings(root.get("github_job_names"), "github_job_names");
        Map<String, String> metrics = strings(root.get("metrics"), "metrics");
        boolean probeAdapter = "gotify_probe".equals(root.get("adapter"));
        if (!canonical && (jobs.isEmpty() || !names.keySet().containsAll(jobs.keySet()))) {
            throw new IOException("Every job role needs a GitHub job display name");
        }
        for (String query : new String[]{"error_rate_query", "latency_p95_ms_query", "availability_query"}) {
            if (!probeAdapter && !metrics.containsKey(query)) throw new IOException("Missing metrics." + query);
        }
        PromotionGate promotionGate = null;
        if (!canonical) {
            if (!(root.get("promotion_gate") instanceof Map<?, ?> gate)) throw new IOException("promotion_gate required");
            promotionGate = new PromotionGate(requiredString(gate, "before"), requiredString(gate, "observe"));
            if (!jobs.containsKey(promotionGate.before()) || !jobs.containsKey(promotionGate.observe())) throw new IOException("Unmapped promotion roles");
        }
        if (!(root.get("environments") instanceof Map<?, ?> rawEnvironments)) {
            throw new IOException("environments must be a mapping");
        }
        Map<String, Environment> environments = new LinkedHashMap<>();
        for (var entry : rawEnvironments.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> settings)) {
                throw new IOException("Environment " + entry.getKey() + " must be a mapping");
            }
            if (probeAdapter) {
                if (!(settings.get("probe_command") instanceof java.util.List<?> raw) || raw.isEmpty()
                        || raw.stream().anyMatch(x -> !(x instanceof String s) || s.isBlank()))
                    throw new IOException("Invalid Gotify probe command");
                environments.put(String.valueOf(entry.getKey()), new Environment(null, null,
                    raw.stream().map(String::valueOf).toList()));
                continue;
            }
            environments.put(String.valueOf(entry.getKey()), new Environment(
                URI.create(requiredString(settings, "ready_url")),
                URI.create(requiredString(settings, "prometheus_url"))));
        }
        if (!(root.get("thresholds") instanceof Map<?, ?> thresholds)) {
            throw new IOException("thresholds must be a mapping");
        }
        double errorRate = number(thresholds, "error_rate_high_gt");
        double latency = number(thresholds, "latency_p95_ms_high_gt");
        if (errorRate < 0 || errorRate > 1 || latency <= 0) {
            throw new IOException("Thresholds must be an error rate in [0,1] and positive latency");
        }
        return new ProjectConfig(name, immutable(jobs), immutable(names), immutable(environments),
            immutable(metrics), promotionGate, errorRate, latency, root.get("max_age_seconds") instanceof Number age ? age.intValue() : 30);
    }

    public Environment environment(String name) {
        Environment value = environments.get(name);
        if (value == null) throw new IllegalArgumentException("Unknown environment: " + name);
        if (!value.probeCommand().isEmpty()) return value;
        String ready = System.getenv("BDI_READY_URL");
        String prometheus = System.getenv("BDI_PROMETHEUS_URL");
        return new Environment(ready == null || ready.isBlank() ? value.readyUrl() : URI.create(ready),
            prometheus == null || prometheus.isBlank() ? value.prometheusUrl() : URI.create(prometheus));
    }

    private static Map<String, String> strings(Object value, String field) throws IOException {
        if (!(value instanceof Map<?, ?> source)) throw new IOException(field + " must be a mapping");
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String item) || item.isBlank()) {
                throw new IOException(field + " entries must be non-empty strings");
            }
            result.put(key, item);
        }
        return result;
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String requiredString(Map<?, ?> source, String key) throws IOException {
        Object value = source.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IOException("Missing " + key);
        return text;
    }

    private static double number(Map<?, ?> source, String key) throws IOException {
        Object value = source.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IOException("Missing numeric " + key);
        }
        return number.doubleValue();
    }
}
