package cicd.classifier;

public record TelemetryClassification(
    String errorRateState,
    String latencyState,
    String availabilityState,
    boolean unstable) {}
