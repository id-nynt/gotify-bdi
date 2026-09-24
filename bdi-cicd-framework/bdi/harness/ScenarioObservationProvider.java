package harness;

import telemetry.Observation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Deterministic observation provider for BDI scenario validation. */
public final class ScenarioObservationProvider implements ObservationProvider {
    private final CopyOnWriteArrayList<Observation> observations = new CopyOnWriteArrayList<>();

    public void publish(Observation observation) {
        observations.removeIf(existing -> existing.entity().equals(observation.entity())
            && existing.property().equals(observation.property()));
        observations.add(observation);
    }

    @Override
    public List<Observation> getObservations() {
        return List.copyOf(observations);
    }
}
