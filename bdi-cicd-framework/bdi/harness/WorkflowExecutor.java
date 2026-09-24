package harness;

/** Generic boundary for executing one atomic workflow entity. */
public interface WorkflowExecutor {
    void runJob(String entity) throws Exception;
}
