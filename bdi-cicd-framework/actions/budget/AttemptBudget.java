package cicd.budget;

@FunctionalInterface
public interface AttemptBudget {
    boolean tryConsume(String key);
}
