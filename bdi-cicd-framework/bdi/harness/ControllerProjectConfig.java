package harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Execution-only project settings. Pipeline order remains in generated AgentSpeak beliefs. */
public record ControllerProjectConfig(String project, String workflowFile,
                                      Map<String, String> jobNames,
                                      Map<String, String> environments,
                                      Map<String, String> releaseSources,
                                      int observationAttempts,
                                      int observationIntervalSeconds) {
    public static ControllerProjectConfig load(Path path) throws IOException {
        Object parsed;
        try (var input = Files.newInputStream(path)) { parsed = new Yaml().load(input); }
        parsed = WorkflowRuntime.unwrap(parsed);
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("controller") instanceof Map<?, ?> controller)) {
            throw new IOException("Project manifest requires a controller mapping");
        }
        String project = text(root, "project");
        String workflow = text(controller, "workflow_file");
        Map<String, String> jobs = strings(controller.get("jobs"), "controller.jobs");
        Map<String, String> environments = controller.get("environments") instanceof Map<?, ?> map && map.isEmpty()
            ? Map.of() : controller.get("environments") == null
                ? Map.of() : strings(controller.get("environments"), "controller.environments");
        int attempts = integer(controller, "observation_attempts", 18, 1, 120);
        int interval = integer(controller, "observation_interval_seconds", 5, 0, 60);
        Map<String, String> sources = (controller.get("release_sources") == null || controller.get("release_sources") instanceof Map<?, ?> m && m.isEmpty()) ? Map.of()
            : strings(controller.get("release_sources"), "controller.release_sources");
        if (!jobs.keySet().containsAll(sources.keySet()) || sources.values().stream().anyMatch(v -> !v.equals("known_good"))) {
            throw new IOException("release_sources must map known jobs to known_good");
        }
        return new ControllerProjectConfig(project, workflow, immutable(jobs), immutable(environments), immutable(sources), attempts, interval);
    }

    private static String text(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IOException("Missing " + key);
        return text;
    }

    private static Map<String, String> strings(Object value, String field) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException(field + " must be a mapping");
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String item = String.valueOf(entry.getValue());
            if (!key.matches("[a-z_][a-z0-9_]*") || item.isBlank()) {
                throw new IOException(field + " contains an invalid entry");
            }
            result.put(key, item);
        }
        if (result.isEmpty()) throw new IOException(field + " must not be empty");
        return result;
    }

    private static int integer(Map<?, ?> map, String key, int fallback, int minimum, int maximum) throws IOException {
        Object raw = map.get(key);
        int value = raw == null ? fallback : raw instanceof Number n ? n.intValue() : -1;
        if (value < minimum || value > maximum) throw new IOException(key + " is outside the supported range");
        return value;
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
