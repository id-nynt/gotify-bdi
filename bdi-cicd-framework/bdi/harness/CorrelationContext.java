package harness;

/** Adapter-owned correlation data; it is intentionally not a Jason belief type. */
public record CorrelationContext(String experimentId, String releaseId, String entity,
                                 String executionId, long githubRunId, String environment) {
    public CorrelationContext withRunId(long runId) {
        return new CorrelationContext(experimentId, releaseId, entity, executionId, runId, environment);
    }
}
