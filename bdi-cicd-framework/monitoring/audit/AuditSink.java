package cicd.audit;

@FunctionalInterface
public interface AuditSink {
    void log(String message);
}
