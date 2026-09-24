package harness;

import telemetry.Observation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Test-only executor that records entities without external systems. */
public final class MockWorkflowExecutor implements WorkflowExecutor {
    private final List<String> executedEntities = new ArrayList<>();
    private final Consumer<Observation> observationSink;

    public MockWorkflowExecutor(Consumer<Observation> observationSink) {
        this.observationSink = observationSink;
    }

    @Override
    public synchronized void runJob(String entity) {
        executedEntities.add(entity);
        observationSink.accept(new Observation(entity, "execution_status", "success", Instant.now()));
        observationSink.accept(new Observation(entity, "duration", 42L, Instant.now()));
    }

    public synchronized List<String> executedEntities() {
        return List.copyOf(executedEntities);
    }
}
