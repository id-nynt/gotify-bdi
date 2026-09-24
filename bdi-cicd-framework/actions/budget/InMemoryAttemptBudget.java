package cicd.budget;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A process-local one-attempt budget, matching the existing *_FAIL_ONCE hook. */
public final class InMemoryAttemptBudget implements AttemptBudget {
    private final Set<String> consumed = ConcurrentHashMap.newKeySet();

    @Override
    public boolean tryConsume(String key) {
        return consumed.add(key);
    }
}
