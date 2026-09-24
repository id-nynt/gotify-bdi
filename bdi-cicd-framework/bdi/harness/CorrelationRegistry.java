package harness;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Correlates current entity observations with the adapter's external execution. */
public final class CorrelationRegistry {
    private final Map<String, CorrelationContext> current = new ConcurrentHashMap<>();
    private final Map<String, String> decisionBeliefs = new ConcurrentHashMap<>();

    public void put(CorrelationContext context) {
        current.put(context.entity(), context);
    }

    public CorrelationContext get(String entity) {
        return current.get(entity);
    }

    public Map<String, CorrelationContext> snapshot() {
        return Map.copyOf(current);
    }

    public void recordDecisionBeliefs(String entity, String beliefs) {
        decisionBeliefs.put(entity, beliefs);
    }

    public String decisionBeliefs(String entity) {
        return decisionBeliefs.getOrDefault(entity, "{}");
    }
}
