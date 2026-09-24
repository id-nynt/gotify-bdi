package harness;

import java.util.*;
import java.util.function.LongSupplier;

/** Ordinary imperative payment pipeline. No Jason plans, beliefs, or reasoning engine. */
public final class ConventionalPolicy {
    public record Settings(int retries, long retryMs, int observations, long observationMs,
        long deadlineMs, int healthyNeeded, int reconciliations, long reconcileMs,
        long maxProductionMs, double maxError, double maxLatency, Set<String> retrySafe,
        Set<String> recoveryTriggers) { }
    public interface IO {
        EntityExecution.Result execute(String entity, int attempt) throws Exception;
        EntityExecution.Result reconcile(String entity, int attempt, int round) throws Exception;
        ProjectTelemetryProvider.Measurement measure(String entity, int attempt, int round, String executionId) throws Exception;
        void event(String name, Map<String, ?> fields);
        void sleep(long ms) throws Exception;
    }
    public record Outcome(String outcome, String recovery) { }
    private final Settings settings;
    private final IO io;
    private final LongSupplier clock;
    private final boolean knownGood;
    public final Map<String, EntityExecution.Result> latest = new LinkedHashMap<>();
    public final Map<String, String> telemetry = new LinkedHashMap<>();
    private final Map<String, Integer> attempts = new HashMap<>();
    public ConventionalPolicy(Settings settings, IO io, LongSupplier clock, boolean knownGood) {
        this.settings = settings; this.io = io; this.clock = clock; this.knownGood = knownGood;
    }
    public Outcome run() throws Exception {
        for (String entity : List.of("build", "test", "security", "staging", "production")) {
            var result = execute(entity, false);
            if (!result.status().equals("success")) return fail(entity, Set.of("unknown", "dispatch_rejected").contains(result.status()) ? result.status() : "failure");
            if (entity.equals("production") && result.durationMs() > settings.maxProductionMs()) return fail(entity, "maintenance_violation");
            if (entity.equals("staging") || entity.equals("production")) {
                String health = observe(entity);
                if (!health.equals("allow")) return fail(entity, health.equals("block") ? "telemetry_block" : "telemetry_unknown");
            }
        }
        return new Outcome("achieved", "not_needed");
    }
    private EntityExecution.Result execute(String entity, boolean recovery) throws Exception {
        for (int attempt = 1; ; attempt++) {
            attempts.put(entity, attempt);
            io.event("conventional_decision", Map.of("decision", "run", "entity", entity, "attempt", attempt));
            var result = io.execute(entity, attempt);
            latest.put(entity, result);
            if (result.status().equals("unknown")) {
                for (int round = 1; round <= settings.reconciliations(); round++) {
                    if (round > 1) io.sleep(settings.reconcileMs());
                    result = io.reconcile(entity, attempt, round);
                    latest.put(entity, result);
                    if (!result.status().equals("unknown")) break;
                }
            }
            if (!recovery && Set.of("transient_failure", "timeout").contains(result.status()) &&
                settings.retrySafe().contains(entity) && attempt <= settings.retries()) {
                io.event("conventional_decision", Map.of("decision", "retry", "entity", entity, "counter", attempt));
                io.sleep(settings.retryMs());
            } else return result;
        }
    }
    private String observe(String entity) throws Exception {
        long start = clock.getAsLong();
        int healthy = 0;
        for (int round = 1; ; round++) {
            io.event("conventional_decision", Map.of("decision", "observe", "entity", entity));
            var m = io.measure(entity, attempts.get(entity), round, latest.get(entity).executionId());
            long elapsed = clock.getAsLong() - start;
            io.event("observation_clock", Map.of("entity", entity, "round", round, "elapsed_ms", elapsed));
            String sample = !m.dataStatus().equals("fresh") ? "unknown" :
                !m.readiness().equals("ready") || m.availability() < 1 || m.errorRate() > settings.maxError() || m.latencyP95Ms() > settings.maxLatency() ? "block" : "allow";
            healthy = sample.equals("allow") ? healthy + 1 : 0;
            if (healthy >= settings.healthyNeeded() && elapsed <= settings.deadlineMs()) { telemetry.put(entity, "allow"); io.event("health_accepted", Map.of("entity", entity, "decision", "allow")); return "allow"; }
            if (round >= settings.observations() || elapsed >= settings.deadlineMs()) {
                String decision = sample.equals("allow") ? "unknown" : sample;
                telemetry.put(entity, decision); io.event("health_accepted", Map.of("entity", entity, "decision", decision)); return decision;
            }
            io.event("conventional_decision", Map.of("decision", "reobserve", "entity", entity, "counter", round));
            io.sleep(settings.observationMs());
        }
    }
    private Outcome fail(String entity, String reason) throws Exception {
        // Unknown execution never authorizes rollback or redispatch.
        if (reason.equals("unknown")) return new Outcome("unknown", "unresolved");
        if (entity.equals("production") && knownGood && settings.recoveryTriggers().contains(reason)) {
            io.event("conventional_recovery_decision", Map.of("source", entity, "entity", "rollback", "reason", reason));
            var result = execute("rollback", true);
            if (result.status().equals("unknown")) return new Outcome("unknown", "unresolved");
            if (!result.status().equals("success")) return new Outcome("stopped", "failed");
            return switch (observe("rollback")) {
                case "allow" -> new Outcome("stopped", "restored");
                case "block" -> new Outcome("stopped", "failed");
                default -> new Outcome("unknown", "unverified");
            };
        }
        return new Outcome(reason.equals("telemetry_unknown") ? "unknown" : "stopped", "not_attempted");
    }
}
