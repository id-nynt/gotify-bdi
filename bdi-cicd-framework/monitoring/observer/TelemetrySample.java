package cicd.observer;

public record TelemetrySample(double errorRate, double latencyP95Ms, double availability) {}
