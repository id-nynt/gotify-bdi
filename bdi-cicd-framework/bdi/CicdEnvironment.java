import jason.asSyntax.Literal;
import jason.asSyntax.Structure;
import jason.asSyntax.Term;
import jason.environment.Environment;

import cicd.action.CicdAction;
import cicd.action.ShellActionExecutor;
import cicd.audit.AuditSink;
import cicd.audit.ConsoleFileAuditSink;
import cicd.budget.AttemptBudget;
import cicd.budget.InMemoryAttemptBudget;
import cicd.classifier.TelemetryClassification;
import cicd.classifier.TelemetryClassifier;
import cicd.classifier.TelemetryThresholds;
import cicd.observer.PrometheusTelemetryObserver;
import cicd.observer.TelemetryObserver;
import cicd.observer.TelemetrySample;
import cicd.policy.ActionPolicy;
import cicd.policy.AllowlistedActionPolicy;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CicdEnvironment extends Environment {
    private Path rootDir;
    private String bashCommand;
    private Path logFile;
    private AuditSink audit;
    private ActionPolicy actionPolicy;
    private ShellActionExecutor shellActionExecutor;
    private TelemetryObserver telemetryObserver;
    private TelemetryClassifier telemetryClassifier;
    private AttemptBudget forcedFailureBudget;
    private ScheduledExecutorService telemetryExecutor;
    private String prometheusUrl;
    private volatile long productionTelemetrySuspendedUntilMillis;
    private final Map<String, String> environmentStates = new ConcurrentHashMap<>();

    @Override
    public void init(String[] args) {
        rootDir = resolveRootDir();
        bashCommand = resolveBashCommand();
        logFile = rootDir.resolve("bdi").resolve("logs").resolve("cicd_environment.log");
        audit = new ConsoleFileAuditSink(logFile);
        actionPolicy = new AllowlistedActionPolicy();
        shellActionExecutor = new ShellActionExecutor(rootDir, bashCommand, audit);
        prometheusUrl = System.getenv().getOrDefault("BDI_PROMETHEUS_URL", "http://localhost:9090");
        telemetryObserver = new PrometheusTelemetryObserver(HttpClient.newHttpClient(), prometheusUrl);
        telemetryClassifier = new TelemetryClassifier(loadThresholds());
        forcedFailureBudget = new InMemoryAttemptBudget();
        log("[CicdEnvironment] root=" + rootDir);
        log("[CicdEnvironment] bash=" + bashCommand);
        log("[CicdEnvironment] prometheus_url=" + prometheusUrl);
        startTelemetryPolling();
    }

    @Override
    public void stop() {
        if (telemetryExecutor != null) {
            telemetryExecutor.shutdownNow();
        }
        super.stop();
    }

    @Override
    public boolean executeAction(String agentName, Structure action) {
        String functor = action.getFunctor();
        try {
            List<String> arguments = new java.util.ArrayList<>();
            for (int index = 0; index < action.getArity(); index++) {
                arguments.add(atom(action.getTerm(index)));
            }
            var authorized = actionPolicy.authorize(functor, arguments, action.getArity());
            if (authorized.isEmpty()) {
                log("[CicdEnvironment] unsupported action: " + action);
                return false;
            }
            CicdAction typedAction = authorized.get();
            switch (typedAction.type()) {
                case BUILD:
                    runStage("build", "status(build, %s)", "build.sh", typedAction.arguments().get(0));
                    return true;
                case TEST:
                    runStage("test", "status(test, %s)", "test.sh", typedAction.arguments().get(0));
                    return true;
                case SECURITY_SCAN:
                    runStage("security_scan", "status(security_scan, %s)", "security_scan.sh", typedAction.arguments().get(0));
                    return true;
                case DEPLOY:
                    runDeploy(typedAction.arguments().get(0), typedAction.arguments().get(1));
                    return true;
                case HEALTH_CHECK:
                    runHealthCheck(typedAction.arguments().get(0));
                    return true;
                case ROLLBACK:
                    runRollback(typedAction.arguments().get(0));
                    return true;
                case OBSERVE:
                    runObservation(typedAction.arguments().get(0), typedAction.arguments().get(1));
                    return true;
                case RECORD_DECISION:
                    recordDecision(typedAction);
                    return true;
            }
            throw new IllegalStateException("unhandled action type " + typedAction.type());
        } catch (Exception exc) {
            log("[CicdEnvironment] action failed before script execution: " + action + " -> " + exc.getMessage());
            return false;
        }
    }

    private void runStage(String stage, String perceptPattern, String scriptName, String version) throws IOException, InterruptedException {
        int exitCode = forcedFailure(stage) ? forcedFailureExitCode(stage) : runScript(scriptName, version);
        updateStatus(perceptPattern, exitCode == 0);
        log("[CicdEnvironment] percept " + String.format(perceptPattern, status(exitCode == 0)));
    }

    private void runDeploy(String candidate, String environment) throws IOException, InterruptedException {
        String stage = "deploy_" + environment;
        suspendTelemetryIfProduction(environment);
        int exitCode = forcedFailure(stage) ? forcedFailureExitCode(stage) : runScript("deploy.sh", environment, candidate);
        suspendTelemetryIfProduction(environment);
        String pattern = "status(deploy(" + environment + "), %s)";
        updateStatus(pattern, exitCode == 0);
        log("[CicdEnvironment] percept " + String.format(pattern, status(exitCode == 0)));
    }

    private void runHealthCheck(String environment) throws IOException, InterruptedException {
        String stage = "health_check_" + environment;
        int exitCode = forcedFailure(stage) ? forcedFailureExitCode(stage) : runScript("health_check.sh", environment);
        String statusPattern = "status(health_check(" + environment + "), %s)";
        updateStatus(statusPattern, exitCode == 0);
        log("[CicdEnvironment] percept " + String.format(statusPattern, status(exitCode == 0)));

        String envPattern = "environment(" + environment + ", %s)";
        updateStatus(envPattern, exitCode == 0, "stable", "unstable");
        log("[CicdEnvironment] percept " + String.format(envPattern, exitCode == 0 ? "stable" : "unstable"));
    }

    private void runRollback(String environment) throws IOException, InterruptedException {
        String stage = "rollback_" + environment;
        suspendTelemetryIfProduction(environment);
        int exitCode = forcedFailure(stage) ? forcedFailureExitCode(stage) : runScript("rollback.sh", environment);
        suspendTelemetryIfProduction(environment);
        String rollbackPattern = "status(rollback(" + environment + "), %s)";
        updateStatus(rollbackPattern, exitCode == 0);
        log("[CicdEnvironment] percept " + String.format(rollbackPattern, status(exitCode == 0)));

        if (exitCode == 0) {
            String envPattern = "environment(" + environment + ", %s)";
            updateStatus(envPattern, true, "stable", "unstable");
            log("[CicdEnvironment] percept " + String.format(envPattern, "stable"));
        }
    }

    private void recordDecision(CicdAction action) {
        if (action.sourceArity() == 1) {
            log("[CicdEnvironment][decision] " + action.arguments().get(0));
        } else if (action.sourceArity() == 2) {
            log("[CicdEnvironment][decision] " + action.arguments().get(0) + " reason=" + action.arguments().get(1));
        } else {
            log("[CicdEnvironment][decision] invalid_record_decision_arity=" + action.sourceArity());
        }
    }

    private void runObservation(String environment, String phase) throws InterruptedException {
        int durationMs = observationDurationMs(environment, phase);
        log("[CicdEnvironment][observe] start environment=" + environment + " phase=" + phase + " duration_ms=" + durationMs);
        Thread.sleep(Math.max(0, durationMs));

        String state = environmentStates.getOrDefault(environment, "unknown");
        boolean stable = state.equals("stable");
        String pattern = "observation(" + environment + ", " + phase + ", %s)";
        removePercept(Literal.parseLiteral(String.format(pattern, "stable")));
        removePercept(Literal.parseLiteral(String.format(pattern, "unstable")));
        removePercept(Literal.parseLiteral(String.format(pattern, "unknown")));
        addPercept(Literal.parseLiteral(String.format(pattern, stable ? "stable" : state.equals("unstable") ? "unstable" : "unknown")));

        log("[CicdEnvironment][observe] complete environment=" + environment + " phase=" + phase + " state=" + state);
        log("[CicdEnvironment] percept " + String.format(pattern, stable ? "stable" : state.equals("unstable") ? "unstable" : "unknown"));
    }

    private int runScript(String scriptName, String... args) throws IOException, InterruptedException {
        return shellActionExecutor.execute(scriptName, args);
    }

    private void startTelemetryPolling() {
        if (!truthy(System.getenv().getOrDefault("BDI_TELEMETRY_ENABLED", "true"))) {
            log("[CicdEnvironment][telemetry] disabled by BDI_TELEMETRY_ENABLED");
            return;
        }

        int intervalSeconds = parseInt(System.getenv("BDI_TELEMETRY_INTERVAL_SECONDS"), 10);
        telemetryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bdi-telemetry-poller");
            thread.setDaemon(true);
            return thread;
        });
        telemetryExecutor.scheduleWithFixedDelay(
            () -> pollTelemetry("production"),
            2,
            Math.max(1, intervalSeconds),
            TimeUnit.SECONDS
        );
        log("[CicdEnvironment][telemetry] polling enabled interval_seconds=" + Math.max(1, intervalSeconds));
    }

    private void pollTelemetry(String environment) {
        try {
            if (environment.equals("production") && System.currentTimeMillis() < productionTelemetrySuspendedUntilMillis) {
                log("[CicdEnvironment][telemetry] skipped production poll during deployment grace window");
                return;
            }

            TelemetrySample sample = telemetryObserver.observe(environment);

            clearTelemetryUnavailable(environment);

            TelemetryClassification classification = telemetryClassifier.classify(sample);
            String errorState = classification.errorRateState();
            String latencyState = classification.latencyState();
            String availabilityState = classification.availabilityState();
            boolean unstable = classification.unstable();

            updateMetric(environment, "error_rate", errorState);
            updateMetric(environment, "latency", latencyState);
            updateMetric(environment, "availability", availabilityState);
            updateStatus("environment(" + environment + ", %s)", !unstable, "stable", "unstable");

            log(String.format(
                Locale.ROOT,
                "[CicdEnvironment][telemetry] %s error_rate=%.4f(%s) latency_p95_ms=%.2f(%s) availability=%.4f(%s) environment=%s",
                environment,
                sample.errorRate(),
                errorState,
                sample.latencyP95Ms(),
                latencyState,
                sample.availability(),
                availabilityState,
                unstable ? "unstable" : "stable"
            ));
        } catch (Exception exc) {
            updateTelemetryUnavailable(environment);
            log("[CicdEnvironment][telemetry] poll_failed environment=" + environment + " reason=" + exc.getMessage());
        }
    }

    private void clearTelemetryUnavailable(String environment) {
        removePercept(Literal.parseLiteral("telemetry(" + environment + ", unavailable)"));
        removePercept(Literal.parseLiteral("network(" + environment + ", suspected)"));
    }

    private void updateTelemetryUnavailable(String environment) {
        addPercept(Literal.parseLiteral("telemetry(" + environment + ", unavailable)"));
        addPercept(Literal.parseLiteral("network(" + environment + ", suspected)"));
        updateStatus("environment(" + environment + ", %s)", false, "stable", "unstable");
        log("[CicdEnvironment] percept telemetry(" + environment + ", unavailable)");
        log("[CicdEnvironment] percept network(" + environment + ", suspected)");
        log("[CicdEnvironment] percept environment(" + environment + ", unstable)");
    }

    private void updateMetric(String environment, String metricName, String value) {
        String pattern = "metric(" + environment + ", " + metricName + ", %s)";
        if (metricName.equals("availability")) {
            removePercept(Literal.parseLiteral(String.format(pattern, "high")));
            removePercept(Literal.parseLiteral(String.format(pattern, "low")));
        } else {
            removePercept(Literal.parseLiteral(String.format(pattern, "high")));
            removePercept(Literal.parseLiteral(String.format(pattern, "normal")));
        }
        addPercept(Literal.parseLiteral(String.format(pattern, value)));
    }

    private TelemetryThresholds loadThresholds() {
        TelemetryThresholds defaults = TelemetryThresholds.defaults();
        double errorRateHighGt = defaults.errorRateHighGt();
        double latencyP95MsHighGt = defaults.latencyP95MsHighGt();
        double availabilityLowLt = defaults.availabilityLowLt();

        Path thresholdFile = rootDir.resolve("telemetry").resolve("thresholds.yml");
        if (!Files.exists(thresholdFile)) {
            log("[CicdEnvironment][telemetry] thresholds file missing; using defaults");
            return defaults;
        }

        try {
            for (String rawLine : Files.readAllLines(thresholdFile, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains(":")) {
                    continue;
                }
                String[] parts = line.split(":", 2);
                String key = parts[0].trim();
                double value = Double.parseDouble(parts[1].trim());
                if (key.equals("error_rate_high_gt")) {
                    errorRateHighGt = value;
                } else if (key.equals("latency_p95_ms_high_gt")) {
                    latencyP95MsHighGt = value;
                } else if (key.equals("availability_low_lt")) {
                    availabilityLowLt = value;
                }
            }
            log("[CicdEnvironment][telemetry] thresholds error_rate_high_gt=" + errorRateHighGt
                + " latency_p95_ms_high_gt=" + latencyP95MsHighGt
                + " availability_low_lt=" + availabilityLowLt);
            return new TelemetryThresholds(errorRateHighGt, latencyP95MsHighGt, availabilityLowLt);
        } catch (Exception exc) {
            log("[CicdEnvironment][telemetry] threshold_read_failed; using defaults reason=" + exc.getMessage());
            return defaults;
        }
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exc) {
            return defaultValue;
        }
    }

    private void suspendTelemetryIfProduction(String environment) {
        if (!environment.equals("production")) {
            return;
        }
        int graceSeconds = parseInt(System.getenv("BDI_TELEMETRY_GRACE_SECONDS"), 15);
        productionTelemetrySuspendedUntilMillis = System.currentTimeMillis() + Math.max(0, graceSeconds) * 1000L;
        log("[CicdEnvironment][telemetry] production grace window seconds=" + Math.max(0, graceSeconds));
    }

    private boolean forcedFailure(String stage) {
        String envStage = envName(stage);
        if (truthy(System.getenv("BDI_FORCE_" + envStage + "_FAIL"))) {
            return true;
        }
        if (truthy(System.getenv("BDI_FORCE_" + envStage + "_FAIL_ONCE"))) {
            return forcedFailureBudget.tryConsume(envStage);
        }
        return false;
    }

    private int forcedFailureExitCode(String stage) {
        log("[CicdEnvironment] forced_failure stage=" + stage + " env=BDI_FORCE_" + envName(stage) + "_FAIL or _FAIL_ONCE");
        return 1;
    }

    private boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1") || normalized.equals("true") || normalized.equals("yes") || normalized.equals("on");
    }

    private String envName(String stage) {
        return stage.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private void updateStatus(String pattern, boolean passed) {
        updateStatus(pattern, passed, "passed", "failed");
    }

    private void updateStatus(String pattern, boolean passed, String passedAtom, String failedAtom) {
        removePercept(Literal.parseLiteral(String.format(pattern, passedAtom)));
        removePercept(Literal.parseLiteral(String.format(pattern, failedAtom)));
        addPercept(Literal.parseLiteral(String.format(pattern, passed ? passedAtom : failedAtom)));
        rememberEnvironmentState(pattern, passed ? passedAtom : failedAtom);
    }

    private void rememberEnvironmentState(String pattern, String value) {
        if (pattern.startsWith("environment(production,")) {
            environmentStates.put("production", value);
        } else if (pattern.startsWith("environment(staging,")) {
            environmentStates.put("staging", value);
        }
    }

    private int observationDurationMs(String environment, String phase) {
        String specificKey = "BDI_OBSERVE_" + envName(environment) + "_" + envName(phase) + "_MS";
        String environmentKey = "BDI_OBSERVE_" + envName(environment) + "_MS";
        int defaultMs = environment.equals("production") ? 20000 : 8000;
        return parseInt(System.getenv(specificKey), parseInt(System.getenv(environmentKey), defaultMs));
    }

    private String status(boolean passed) {
        return passed ? "passed" : "failed";
    }

    private String atom(Term term) {
        return term.toString().replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private Path resolveRootDir() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("cicd").resolve("actions"))) {
            return cwd;
        }
        if (cwd.getFileName() != null && cwd.getFileName().toString().equals("bdi")) {
            Path parent = cwd.getParent();
            if (parent != null && Files.exists(parent.resolve("cicd").resolve("actions"))) {
                return parent;
            }
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.exists(parent.resolve("cicd").resolve("actions"))) {
            return parent;
        }
        return cwd;
    }

    private String resolveBashCommand() {
        String windowsGitBash = "C:\\Program Files\\Git\\bin\\bash.exe";
        if (new File(windowsGitBash).exists()) {
            return windowsGitBash;
        }
        return "bash";
    }

    private void log(String message) {
        if (audit == null) {
            System.out.println(message);
        } else {
            audit.log(message);
        }
    }
}
