package harness;

import telemetry.Observation;

/** Converts a normalized observation into a Jason belief literal. */
public interface BeliefAdapter {
    String convert(Observation observation);
}
