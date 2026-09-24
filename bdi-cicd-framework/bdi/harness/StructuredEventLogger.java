package harness;

import telemetry.Observation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** Small JSON-lines logger for evaluating cross-component transitions. */
public final class StructuredEventLogger {
    private static final Logger LOG = Logger.getLogger(StructuredEventLogger.class.getName());
    private final Path output;

    public StructuredEventLogger(Path output) {
        this.output = output;
    }

    public synchronized void event(String event, CorrelationContext context,
                                    Map<String, ?> fields) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("timestamp", Instant.now().toString());
        all.put("event", event);
        if (context != null) {
            all.put("experiment_id", context.experimentId());
            all.put("release_id", context.releaseId());
            all.put("entity", context.entity());
            all.put("execution_id", context.executionId());
            all.put("github_run_id", context.githubRunId() == 0 ? null : context.githubRunId());
            all.put("deployment_environment", context.environment());
        }
        all.putAll(fields);
        String commonPath = System.getenv("EXPERIMENT_EVENTS_FILE");
        if (commonPath != null && !commonPath.isBlank()) {
            Map<String, Object> common = normalized(all, System.getenv().getOrDefault("EXPERIMENT_MECHANISM", "bdi"),
                System.getenv().getOrDefault("BDI_CAMPAIGN_ID", ""));
            try {
                Files.writeString(Path.of(commonPath), toJson(common) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException error) { throw new java.io.UncheckedIOException("Cannot retain common experiment evidence", error); }
        }
        String line = toJson(all);
        LOG.info(line);
        if (output != null) {
            try {
                Files.createDirectories(output.toAbsolutePath().getParent());
                Files.writeString(output, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException error) {
                LOG.warning("structured_log_file_error=" + error.getMessage());
            }
        }
    }

    static Map<String, Object> normalized(Map<String, Object> source, String mechanism, String campaign) {
        var result = new LinkedHashMap<String, Object>(source);
        String name = String.valueOf(source.get("event"));
        result.put("event", switch (name) {
            case "controller_started" -> "campaign_started";
            case "entity_execution_started" -> "action_started";
            case "entity_execution_finished" -> "action_finished";
            case "bdi_decision", "conventional_decision" -> "decision";
            case "bdi_reconciliation", "conventional_reconciliation" -> "reconciliation";
            case "bdi_recovery_decision", "conventional_recovery_decision" -> "recovery_started";
            case "controller_pause" -> "deployment_ready";
            case "telemetry_measurement" -> "observation";
            case "controller_finished" -> "campaign_finished";
            default -> name;
        });
        result.put("schema_version", 1);
        result.put("mechanism", mechanism);
        result.put("campaign_id", campaign);
        return result;
    }

    public void observation(Observation observation, CorrelationContext context, String belief) {
        event("observation_normalized", context, Map.of(
            "property", observation.property(), "value", observation.value(), "belief", belief));
    }

    private static String toJson(Map<String, ?> fields) {
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> field : fields.entrySet()) {
            if (!first) result.append(',');
            first = false;
            result.append('"').append(escape(field.getKey())).append("\":");
            Object value = field.getValue();
            if (value == null) result.append("null");
            else if (value instanceof Number || value instanceof Boolean) result.append(value);
            else result.append('"').append(escape(String.valueOf(value))).append('"');
        }
        return result.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') escaped.append('\\').append(c);
            else if (c < 32) escaped.append(String.format("\\u%04x", (int)c));
            else escaped.append(c);
        }
        return escaped.toString();
    }
}
