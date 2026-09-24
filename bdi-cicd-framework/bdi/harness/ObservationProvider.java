package harness;

import telemetry.Observation;
import java.util.List;

/** Generic source of normalized observations. */
public interface ObservationProvider {
    List<Observation> getObservations() throws Exception;
}
