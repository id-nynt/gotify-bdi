package harness;

import jason.asSyntax.Literal;
import jason.asSyntax.Structure;
import jason.asSyntax.Term;
import jason.environment.Environment;
import telemetry.Observation;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic Jason environment. It transports actions and observations only;
 * it contains no workflow policy or BDI decision logic.
 */
public class ObservationEnvironment extends Environment {
    private static final Logger LOG = Logger.getLogger(ObservationEnvironment.class.getName());

    private final WorkflowExecutor executor;
    private final ObservationProvider provider;
    private final BeliefAdapter beliefAdapter;
    private final CorrelationRegistry correlationRegistry;
    private final StructuredEventLogger structuredLogger;
    private final Map<String, Literal> currentBeliefs = new HashMap<>();
    private final Map<String, Instant> lastEventTimestamps = new HashMap<>();
    private volatile boolean polling;
    private Thread observationThread;

    public ObservationEnvironment(WorkflowExecutor executor, ObservationProvider provider,
                                  BeliefAdapter beliefAdapter) {
        this(executor, provider, beliefAdapter, null, null);
    }

    public ObservationEnvironment(WorkflowExecutor executor, ObservationProvider provider,
                                  BeliefAdapter beliefAdapter, CorrelationRegistry correlationRegistry,
                                  StructuredEventLogger structuredLogger) {
        this.executor = executor;
        this.provider = provider;
        this.beliefAdapter = beliefAdapter;
        this.correlationRegistry = correlationRegistry;
        this.structuredLogger = structuredLogger;
    }

    @Override
    public void init(String[] args) {
        super.init(args);
        polling = true;
        observationThread = new Thread(this::pollObservations, "normalized-observation-poller");
        observationThread.setDaemon(true);
        observationThread.start();
        console("[BDI Environment] observation environment started");
    }

    @Override
    public boolean executeAction(String agentName, Structure action) {
        if (!"run_job".equals(action.getFunctor()) || action.getArity() != 1) {
            LOG.warning(() -> "Unsupported external action: " + action);
            return false;
        }
        Term entityTerm = action.getTerm(0);
        String entity = entityTerm.toString();
        try {
            console("[BDI Environment] action agent=" + agentName + " run_job entity=" + entity);
            structured("bdi_action_requested", entity, Map.of(
                "agent", agentName,
                "triggered_event", "run_job(" + entity + ")",
                "selected_plan", "run_entity",
                "selected_action", "run_job(" + entity + ")",
                "relevant_beliefs", currentBeliefsSnapshot().toString()));
            if (correlationRegistry != null) {
                correlationRegistry.recordDecisionBeliefs(entity, currentBeliefsSnapshot().toString());
            }
            clearExecutionPercepts(entity);
            executor.runJob(entity);
            return true;
        } catch (Exception error) {
            LOG.log(Level.WARNING, "Workflow executor failed for entity " + entity, error);
            console("[BDI Environment] action failed entity=" + entity + " error=" + error.getMessage());
            structured("execution_error", entity, Map.of("error", String.valueOf(error.getMessage())));
            return false;
        }
    }

    protected void publishObservations(List<Observation> observations) {
        for (Observation observation : observations) {
            String beliefText = beliefAdapter.convert(observation);
            Literal belief = Literal.parseLiteral(beliefText);
            String key = observation.entity() + "|" + observation.property();
            Literal previous = currentBeliefs.put(key, belief);
            boolean executionEvent = observation.property().equals("execution_status")
                || observation.property().equals("duration");
            if (executionEvent) {
                Instant previousTimestamp = lastEventTimestamps.put(key, observation.timestamp());
                if (observation.timestamp().equals(previousTimestamp)) continue;
            }
            if (!executionEvent && previous != null && previous.equals(belief)) continue;
            // Remove the exact prior percept before re-adding it. This matters
            // when two consecutive attempts produce the same normalized value:
            // Jason must receive a new percept event for the new attempt.
            if (previous != null) removePercept(previous);
            addPercept(belief);
            console("[BDI Environment] observation=" + observation.toJson() + " belief=" + beliefText);
            structured("percept_published", observation.entity(), Map.of(
                "property", observation.property(),
                "value", observation.value(),
                "belief", beliefText,
                "reconsideration", true));
        }
    }

    private void structured(String event, String entity, Map<String, ?> fields) {
        if (structuredLogger == null) return;
        CorrelationContext context = correlationRegistry == null ? null : correlationRegistry.get(entity);
        structuredLogger.event(event, context, fields);
    }

    protected synchronized Map<String, Literal> currentBeliefsSnapshot() {
        return Map.copyOf(currentBeliefs);
    }

    private synchronized void clearExecutionPercepts(String entity) {
        for (String property : List.of("execution_status", "duration")) {
            String key = entity + "|" + property;
            Literal previous = currentBeliefs.remove(key);
            lastEventTimestamps.remove(key);
            if (previous != null) removePercept(previous);
        }
    }

    private void pollObservations() {
        while (polling) {
            try {
                publishObservations(provider.getObservations());
            } catch (Exception error) {
            LOG.log(Level.WARNING, "Observation provider unavailable", error);
            console("[BDI Environment] observation provider unavailable: " + error.getMessage());
            }
            try {
                Thread.sleep(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void stop() {
        polling = false;
        if (observationThread != null) observationThread.interrupt();
        console("[BDI Environment] observation environment stopped at " + Instant.now());
        super.stop();
    }

    /** Console-first output matching the sample bdi environment behavior. */
    private void console(String message) {
        System.out.println(message);
        System.out.flush();
    }
}
