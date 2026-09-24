package harness;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Deterministic executor used only by local Jason reasoning experiments. */
public final class ScenarioEntityExecution implements EntityExecution {
    private final String scenario;
    private final Map<String, Result> results = new HashMap<>();
    private final Map<String, Integer> calls = new HashMap<>();

    public ScenarioEntityExecution(String scenario) { this.scenario = scenario; }

    @Override
    public Result execute(String entity, int attempt) {
        int count = calls.merge(entity, 1, Integer::sum);
        String status = "success";
        if (entity.equals("test") && scenario.equals("transient_test_failure") && count == 1) status = "transient_failure";
        if (entity.equals("test") && scenario.equals("exhausted_test_failure")) status = "transient_failure";
        if (entity.equals("staging") && scenario.equals("staging_failure")) status = "failure";
        if (entity.equals("production") && scenario.equals("production_failure")) status = "failure";
        if (entity.equals("rollback") && scenario.equals("rollback_failure")) status = "failure";
        if (entity.equals("production") && scenario.equals("execution_uncertain")) status = "unknown";
        if (entity.equals("production") && scenario.equals("reconciled_success")) status = "unknown";
        if (entity.equals("test") && scenario.equals("reconciled_failure") && count == 1) status = "unknown";
        if (entity.equals("test") && scenario.equals("deterministic_test_failure")) status = "failure";
        if (entity.equals("production") && scenario.equals("production_retry") && count == 1) status = "transient_failure";
        if (entity.equals("build") && scenario.equals("dispatch_rejected")) status = "dispatch_rejected";
        Result result = new Result(status, 42, "scenario-" + UUID.randomUUID(), 0,
            "scenario://" + scenario + "/" + entity + "/" + attempt);
        results.put(entity, result);
        return result;
    }

    @Override public Result reconcile(String entity, int attempt) {
        Result previous = results.get(entity);
        String status = scenario.equals("reconciled_success") ? "success"
            : scenario.equals("reconciled_failure") ? "transient_failure" : "unknown";
        return new Result(status, 43, previous.executionId(), previous.githubRunId(), previous.runUrl());
    }
}
