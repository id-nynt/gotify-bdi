package harness;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class CampaignIntegrityTest {
    @TempDir Path directory;
    @Test void startupRejectsModifiedGeneratedArtifacts() throws Exception {
        var manifest = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        for(var entry:java.util.Map.of("03_workflow_model.yaml","workflow_sha256","controller_agent.asl","generated_agent_sha256","controller.mas2j","mas_sha256").entrySet()) {
            byte[] content=entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(directory.resolve(entry.getKey()),content);
            manifest.put(entry.getValue(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content)));
        }
        Path path=directory.resolve("generation-manifest.json");Files.writeString(path,manifest.toString());
        ControllerMain.verifyCampaign(path);
        Files.writeString(directory.resolve("controller_agent.asl"),"changed");
        assertThrows(IllegalStateException.class,()->ControllerMain.verifyCampaign(path));
    }
}
