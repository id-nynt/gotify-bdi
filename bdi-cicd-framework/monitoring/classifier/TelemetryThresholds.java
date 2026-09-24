package cicd.classifier;

public record TelemetryThresholds(double errorRateHighGt, double latencyP95MsHighGt, double availabilityLowLt) {
    public static TelemetryThresholds defaults() {
        return new TelemetryThresholds(0.05, 500.0, 0.99);
    }
}
