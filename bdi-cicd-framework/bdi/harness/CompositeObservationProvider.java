package harness;

import telemetry.Observation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Combines runtime telemetry with execution observations from an adapter. */
public final class CompositeObservationProvider implements ObservationProvider {
    private final ObservationProvider delegate;
    private final CorrelationRegistry registry;
    private final StructuredEventLogger logger;
    private final ConcurrentLinkedQueue<Observation> executionObservations = new ConcurrentLinkedQueue<>();

    public CompositeObservationProvider(ObservationProvider delegate) {
        this(delegate, null, null);
    }

    public CompositeObservationProvider(ObservationProvider delegate, CorrelationRegistry registry,
                                        StructuredEventLogger logger) {
        this.delegate = delegate;
        this.registry = registry;
        this.logger = logger;
    }

    public void publish(Observation observation) {
        executionObservations.add(observation);
    }

    @Override
    public List<Observation> getObservations() throws Exception {
        List<Observation> observations = new ArrayList<>(delegate.getObservations());
        Observation observation;
        while ((observation = executionObservations.poll()) != null) observations.add(observation);
        if (logger != null) {
            for (Observation item : observations) {
                logger.event("observation_collected", registry == null ? null : registry.get(item.entity()),
                    java.util.Map.of("property", item.property(), "value", item.value()));
            }
        }
        return observations;
    }
}
