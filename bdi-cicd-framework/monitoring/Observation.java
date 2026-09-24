package telemetry;

import java.time.Instant;

/** Stable observation contract for consumers such as the Java/Jason environment. */
public record Observation(String entity, String property, Object value, Instant timestamp) {
    public String toJson() {
        return "{\"entity\":\"" + escape(entity) + "\","
            + "\"property\":\"" + escape(property) + "\","
            + "\"value\":" + jsonValue(value) + ","
            + "\"timestamp\":\"" + timestamp + "\"}";
    }

    private static String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "\"" + escape(value.toString()) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
