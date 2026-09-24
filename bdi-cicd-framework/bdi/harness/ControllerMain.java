package harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import jason.infra.local.RunLocalMAS;

/** Acquires a campaign lock, starts the generated Jason controller, and maps its final outcome to an exit code. */
public final class ControllerMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ControllerMain() { }

    public static void main(String[] args) throws Exception {
        Path lockPath = Path.of(value("BDI_LOCK_FILE", "build/controller.lock")).toAbsolutePath();
        Files.createDirectories(lockPath.getParent());
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another controller holds " + lockPath);
            verifyCampaign(Path.of(System.getenv("BDI_MANIFEST_FILE")));
            Path result = Path.of(value("BDI_RESULT_FILE", "build/controller-result.json"));
            Files.deleteIfExists(result);
            if (Boolean.parseBoolean(value("BDI_RECONCILE_ONLY", "false"))) {
                var config = ControllerProjectConfig.load(Path.of(System.getenv("BDI_PROJECT_FILE")));
                var journal = new StructuredEventLogger(Path.of(System.getenv("BDI_JOURNAL_FILE")));
                var execution = new GitHubEntityExecution(config, journal);
                String evidence = value("BDI_REJECTED_DISPATCH_EVIDENCE", "");
                var observation = evidence.isBlank() ? execution.reconcilePending()
                    : execution.reconcileRejectedDispatch(Path.of(evidence), result.toAbsolutePath().getParent());
                Files.writeString(result, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                    java.util.Map.of("operation", "reconcile_only", "execution", observation,
                        "candidate_goal_evaluated", false)) + "\n");
                if (observation.status().equals("unknown")) System.exit(2);
                return;
            }
            boolean gui = Boolean.parseBoolean(value("BDI_GUI", "false"));
            if (gui && java.awt.GraphicsEnvironment.isHeadless()) {
                throw new IllegalStateException("--gui requires a desktop display; use a desktop terminal or omit --gui");
            }
            try { RunLocalMAS.main(new String[]{value("BDI_MAS_FILE", "controller.mas2j"), "--log-conf",
                value("BDI_LOG_CONFIG", gui ? "logging-gui.properties" : "logging.properties")}); } catch (Exception error) {
                error.printStackTrace(); System.exit(2);
            }
            if (!Files.exists(result)) throw new IllegalStateException("Controller stopped without a result");
            String outcome = JSON.readTree(Files.readString(result)).path("outcome").asText("unknown");
            if (outcome.equals("achieved")) return;
            System.exit(outcome.equals("stopped") ? 1 : 2);
        }
    }

    static void verifyCampaign(Path manifest) throws Exception {
        var record = JSON.readTree(Files.readString(manifest));
        Path directory = manifest.toAbsolutePath().getParent();
        if (record.has("conventional_policy_sha256")) {
            String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(directory.resolve("conventional-policy.json"))));
            if (!actual.equals(record.path("conventional_policy_sha256").asText())) throw new IllegalStateException("Conventional policy snapshot changed");
        }
        for (var entry : java.util.Map.of("03_workflow_model.yaml", "workflow_sha256",
                "controller_agent.asl", "generated_agent_sha256", "controller.mas2j", "mas_sha256").entrySet()) {
            String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(directory.resolve(entry.getKey()))));
            if (!actual.equals(record.path(entry.getValue()).asText()))
                throw new IllegalStateException("Campaign artifact changed after generation: " + entry.getKey());
        }
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value;
    }
}
