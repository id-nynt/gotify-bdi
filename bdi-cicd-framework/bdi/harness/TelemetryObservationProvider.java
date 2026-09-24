package harness;

import telemetry.Observation;
import telemetry.TelemetryAdapter;

import java.util.List;

/** Production observation provider backed by the generic telemetry adapter. */
public final class TelemetryObservationProvider implements ObservationProvider {
    private final TelemetryAdapter adapter;

    public TelemetryObservationProvider(TelemetryAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public List<Observation> getObservations() {
        return adapter.pollOnce();
    }
}
