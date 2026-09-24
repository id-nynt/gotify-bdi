package cicd.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Preserves the environment's console-first, best-effort append-only audit behavior. */
public final class ConsoleFileAuditSink implements AuditSink {
    private final Path logFile;

    public ConsoleFileAuditSink(Path logFile) {
        this.logFile = logFile;
    }

    @Override
    public void log(String message) {
        System.out.println(message);
        if (logFile == null) {
            return;
        }
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, message + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Console logging still works if file logging is unavailable.
        }
    }
}
