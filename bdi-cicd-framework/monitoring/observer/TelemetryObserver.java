package cicd.observer;

import java.io.IOException;

public interface TelemetryObserver {
    TelemetrySample observe(String environment) throws IOException, InterruptedException;
}
