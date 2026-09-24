package harness;

import telemetry.Observation;

import java.math.BigDecimal;

/** Generic normalized-observation to Jason-belief mapping. */
public final class JasonBeliefAdapter implements BeliefAdapter {
    @Override
    public String convert(Observation observation) {
        String entity = atom(observation.entity());
        String property = observation.property();
        Object value = observation.value();

        return switch (property) {
            case "execution_status" -> "status(" + entity + "," + statusValue(value) + ")";
            case "health" -> "health(" + entity + "," + atom(String.valueOf(value)) + ")";
            case "gate" -> "gate(" + entity + "," + atom(String.valueOf(value)) + ")";
            case "duration" -> "duration(" + entity + "," + number(value) + ")";
            case "error_rate", "latency" -> "metric(" + entity + "," + atom(property) + "," + number(value) + ")";
            default -> "observation(" + entity + "," + atom(property) + "," + jasonValue(value) + ")";
        };
    }

    private static String statusValue(Object value) {
        return "success".equalsIgnoreCase(String.valueOf(value)) ? "success" : "fail";
    }

    private static String number(Object value) {
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    private static String jasonValue(Object value) {
        if (value instanceof Number) return number(value);
        return atom(String.valueOf(value));
    }

    private static String atom(String value) {
        if (value.matches("[a-zA-Z_][a-zA-Z0-9_]*")) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
