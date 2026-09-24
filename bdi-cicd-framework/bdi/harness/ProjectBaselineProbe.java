package harness;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import telemetry.Observation;

/** One-shot, read-only check before wiring observations into Jason or a CI gate. */
public final class ProjectBaselineProbe {
    private ProjectBaselineProbe() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (index + 1 >= args.length || !args[index].startsWith("--")) {
                throw new IllegalArgumentException("Use --project FILE --environment NAME --jobs-json FILE or live GitHub settings");
            }
            options.put(args[index].substring(2), args[index + 1]);
        }
        Path projectPath = Path.of(options.getOrDefault("project", "../models/payment_project.yaml"));
        String environment = options.getOrDefault("environment", "local");
        ProjectConfig project = ProjectConfig.load(projectPath);
        Path fixture = options.containsKey("jobs-json") ? Path.of(options.get("jobs-json")) : null;
        String repository = options.getOrDefault("repository", System.getenv("GITHUB_REPOSITORY"));
        String runText = options.getOrDefault("run-id", System.getenv().getOrDefault("GITHUB_RUN_ID", "0"));
        if (fixture == null && (repository == null || runText.equals("0"))) {
            throw new IllegalArgumentException("Set --jobs-json FILE or provide --repository and --run-id");
        }
        GitHubRunObserver github = new GitHubRunObserver(project, repository, Long.parseLong(runText),
            System.getenv("GITHUB_TOKEN"), fixture);
        ProjectTelemetryProvider telemetry = new ProjectTelemetryProvider(project, environment);
        for (var job : github.observe().values()) {
            System.out.println(new Observation(job.role(), "execution_status", job.conclusion(),
                java.time.Instant.now()).toJson());
            System.out.println(new Observation(job.role(), "duration", job.durationMs(),
                java.time.Instant.now()).toJson());
        }
        for (Observation item : telemetry.getObservations()) System.out.println(item.toJson());
        ProjectTelemetryProvider.Assessment assessment = telemetry.assess();
        System.out.println("assessment=" + assessment.decision() + " reason=" + assessment.reason());
    }
}
