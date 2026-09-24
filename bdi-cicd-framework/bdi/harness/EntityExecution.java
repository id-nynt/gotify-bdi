package harness;

public interface EntityExecution {
    record Result(String status, long durationMs, String executionId, long githubRunId, String runUrl) { }
    Result execute(String entity, int attempt) throws Exception;
    default Result reconcile(String entity, int attempt) throws Exception {
        return new Result("unknown", 0, "unresolved", 0, "");
    }
}
