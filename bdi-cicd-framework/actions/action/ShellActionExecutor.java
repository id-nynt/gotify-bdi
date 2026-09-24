package cicd.action;

import cicd.audit.AuditSink;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Executes the existing shell action scripts without interpreting their output. */
public final class ShellActionExecutor {
    private final Path rootDir;
    private final String bashCommand;
    private final AuditSink audit;

    public ShellActionExecutor(Path rootDir, String bashCommand, AuditSink audit) {
        this.rootDir = rootDir;
        this.bashCommand = bashCommand;
        this.audit = audit;
    }

    public int execute(String scriptName, String... arguments) throws IOException, InterruptedException {
        Path script = rootDir.resolve("cicd").resolve("actions").resolve(scriptName);
        List<String> command = new ArrayList<>();
        command.add(bashCommand);
        command.add(script.toString());
        command.addAll(List.of(arguments));

        audit.log("[CicdEnvironment] action " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(rootDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                audit.log("[CicdEnvironment][script] " + line);
            }
        }
        int exitCode = process.waitFor();
        audit.log("[CicdEnvironment] exit_code=" + exitCode + " script=" + scriptName);
        return exitCode;
    }
}
