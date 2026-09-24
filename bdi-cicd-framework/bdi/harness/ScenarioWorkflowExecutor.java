package harness;

import telemetry.Observation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Executes a deterministic, scenario-defined sequence without external systems. */
public final class ScenarioWorkflowExecutor implements WorkflowExecutor {
    public record Outcome(String status, long durationMs) { }

    private final Map<String, List<Outcome>> outcomes;
    private final Map<String, Integer> nextOutcome = new LinkedHashMap<>();
    private final List<String> executedEntities = new ArrayList<>();
    private final Consumer<Observation> observationSink;

    public ScenarioWorkflowExecutor(Map<String, List<Outcome>> outcomes,
                                    Consumer<Observation> observationSink) {
        this.outcomes = outcomes;
        this.observationSink = observationSink;
    }

    @Override
    public synchronized void runJob(String entity) {
        executedEntities.add(entity);
        List<Outcome> entityOutcomes = outcomes.getOrDefault(entity,
            List.of(new Outcome("failure", 1)));
        int index = nextOutcome.getOrDefault(entity, 0);
        Outcome outcome = entityOutcomes.get(Math.min(index, entityOutcomes.size() - 1));
        nextOutcome.put(entity, index + 1);
        Instant now = Instant.now();
        observationSink.accept(new Observation(entity, "execution_status",
            outcome.status(), now));
        observationSink.accept(new Observation(entity, "duration",
            outcome.durationMs(), now));
    }

    public synchronized List<String> executedEntities() {
        return List.copyOf(executedEntities);
    }
}
