package cicd.classifier;

import cicd.observer.TelemetrySample;

/** Pure implementation of the current strict threshold rules. */
public final class TelemetryClassifier {
    private final TelemetryThresholds thresholds;

    public TelemetryClassifier(TelemetryThresholds thresholds) {
        this.thresholds = thresholds;
    }

    public TelemetryClassification classify(TelemetrySample sample) {
        String error = sample.errorRate() > thresholds.errorRateHighGt() ? "high" : "normal";
        String latency = sample.latencyP95Ms() > thresholds.latencyP95MsHighGt() ? "high" : "normal";
        String availability = sample.availability() < thresholds.availabilityLowLt() ? "low" : "high";
        return new TelemetryClassification(error, latency, availability,
            error.equals("high") || latency.equals("high") || availability.equals("low"));
    }
}
