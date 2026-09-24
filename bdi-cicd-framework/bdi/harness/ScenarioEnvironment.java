package harness;

import jason.asSyntax.Literal;
import telemetry.Observation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Jason environment for deterministic BDI validation scenarios. */
public final class ScenarioEnvironment extends ObservationEnvironment {
    private static final Logger LOG = Logger.getLogger(ScenarioEnvironment.class.getName());
    private final String scenario;
    private final ScenarioWorkflowExecutor executor;
    private final ScenarioObservationProvider provider;
    private volatile boolean shutdownScheduled;

    private record Components(String scenario, ScenarioWorkflowExecutor executor,
                              ScenarioObservationProvider provider) { }

    public ScenarioEnvironment() {
        this(createComponents(System.getProperty("bdi.scenario", "scenario1")));
    }

    private ScenarioEnvironment(Components components) {
        super(components.executor(), components.provider(), new JasonBeliefAdapter());
        this.scenario = components.scenario();
        this.executor = components.executor();
        this.provider = components.provider();
    }

    @Override
    public void init(String[] args) {
        super.init(args);
        if (scenario.equals("scenario7") || scenario.equals("scenario8")) {
            addPercept(Literal.parseLiteral("workflow_stopped"));
            addPercept(Literal.parseLiteral("running(production)"));
            addPercept(Literal.parseLiteral("run_attempt(production,1)"));
            LOG.info("initial_beliefs=[workflow_stopped,running(production),run_attempt(production,1)]");
            provider.publish(new Observation("production", "execution_status", "success", Instant.now()));
            if (scenario.equals("scenario7")) {
                provider.publish(new Observation("production", "duration", 100001L, Instant.now()));
            } else {
                provider.publish(new Observation("production", "duration", 1L, Instant.now()));
            }
        } else {
            LOG.info("initial_beliefs=[workflow_active]");
        }
    }

    @Override
    protected synchronized void publishObservations(List<Observation> observations) {
        super.publishObservations(observations);
        if (!shutdownScheduled && executor.executedEntities().size() >= expectedActions(scenario)) {
            shutdownScheduled = true;
            Thread shutdown = new Thread(() -> {
                try {
                    Thread.sleep(750);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                try {
                    getEnvironmentInfraTier().getRuntimeServices().stopMAS();
                } catch (Exception error) {
                    LOG.warning("Unable to stop scenario MAS: " + error.getMessage());
                    stop();
                }
            }, "scenario-shutdown");
            shutdown.setDaemon(true);
            shutdown.start();
        }
    }

    @Override
    public void stop() {
        LOG.info("scenario=" + scenario + " run_job_calls=" + executor.executedEntities());
        LOG.info("final_beliefs=" + currentBeliefsSnapshot());
        super.stop();
    }

    public static Map<String, List<ScenarioWorkflowExecutor.Outcome>> definition(String scenario) {
        ScenarioWorkflowExecutor.Outcome ok = new ScenarioWorkflowExecutor.Outcome("success", 42);
        ScenarioWorkflowExecutor.Outcome fail = new ScenarioWorkflowExecutor.Outcome("failure", 42);
        Map<String, List<ScenarioWorkflowExecutor.Outcome>> result = new LinkedHashMap<>();
        switch (scenario) {
            case "scenario1" -> {
                add(result, "build", ok); add(result, "test", ok); add(result, "security", ok);
                add(result, "staging", ok); add(result, "production", ok);
            }
            case "scenario2" -> {
                add(result, "build", ok); add(result, "test", new ScenarioWorkflowExecutor.Outcome("failure", 41), ok); add(result, "security", ok);
                add(result, "staging", ok); add(result, "production", ok);
            }
            case "scenario3" -> {
                add(result, "build", ok); add(result, "test", new ScenarioWorkflowExecutor.Outcome("failure", 41), new ScenarioWorkflowExecutor.Outcome("failure", 42));
            }
            case "scenario4" -> {
                add(result, "build", ok); add(result, "test", ok); add(result, "security", ok);
                add(result, "staging", ok); add(result, "production", new ScenarioWorkflowExecutor.Outcome("failure", 41), ok);
            }
            case "scenario5" -> {
                add(result, "build", ok); add(result, "test", ok); add(result, "security", ok);
                add(result, "staging", ok); add(result, "production", fail, fail);
                add(result, "rollback_production", ok);
            }
            case "scenario6" -> {
                add(result, "build", ok); add(result, "test", ok); add(result, "security", ok);
                add(result, "staging", ok); add(result, "production", fail, fail);
                add(result, "rollback_production", fail);
            }
            case "scenario7", "scenario8" -> add(result, "rollback_production", ok);
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }
        return result;
    }

    private static Components createComponents(String scenario) {
        ScenarioObservationProvider provider = new ScenarioObservationProvider();
        ScenarioWorkflowExecutor executor = new ScenarioWorkflowExecutor(definition(scenario), provider::publish);
        return new Components(scenario, executor, provider);
    }

    private static void add(Map<String, List<ScenarioWorkflowExecutor.Outcome>> map,
                            String entity, ScenarioWorkflowExecutor.Outcome... outcomes) {
        map.put(entity, List.of(outcomes));
    }

    private static int expectedActions(String scenario) {
        return switch (scenario) {
            case "scenario1" -> 5;
            case "scenario2" -> 6;
            case "scenario3" -> 3;
            case "scenario4" -> 6;
            case "scenario5", "scenario6" -> 7;
            case "scenario7", "scenario8" -> 1;
            default -> Integer.MAX_VALUE;
        };
    }
}
