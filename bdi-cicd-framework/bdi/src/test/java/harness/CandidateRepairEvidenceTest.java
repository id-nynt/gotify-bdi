package harness;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CandidateRepairEvidenceTest {
    @Test void malformedOrDifferentDeploymentCannotAuthorizeRepair() throws Exception {
        var mapper=new ObjectMapper();
        var evidence=mapper.readTree("{\"action\":\"diagnose\",\"expected_execution_id\":\"v2\",\"status\":\"observed\",\"before\":{\"deployment_execution_id\":\"v2\",\"container_id\":\"container-v2\",\"app_state\":\"stopped\",\"dependency_ready\":true}}");
        assertEquals("app_stopped",ControllerEnvironment.classifyRepairEvidence(evidence,"v2",false));
        assertEquals("unknown",ControllerEnvironment.classifyRepairEvidence(evidence,"v1",false));
        ((com.fasterxml.jackson.databind.node.ObjectNode)evidence.path("before")).remove("dependency_ready");
        assertEquals("unknown",ControllerEnvironment.classifyRepairEvidence(evidence,"v2",false));
        assertEquals("unknown",ControllerEnvironment.classifyRepairEvidence(mapper.readTree("{}"),"v2",false));
    }
    @Test void restartMustRetainContainerAndDeploymentIdentity() throws Exception {
        var evidence=new ObjectMapper().readTree("{\"action\":\"restart\",\"expected_execution_id\":\"v2\",\"status\":\"executed\",\"before\":{\"deployment_execution_id\":\"v2\",\"container_id\":\"same\",\"dependency_ready\":true},\"after\":{\"deployment_execution_id\":\"v2\",\"container_id\":\"other\"}}");
        assertEquals("unknown",ControllerEnvironment.classifyRepairEvidence(evidence,"v2",true));
        ((com.fasterxml.jackson.databind.node.ObjectNode)evidence.path("after")).put("container_id","same");
        assertEquals("executed",ControllerEnvironment.classifyRepairEvidence(evidence,"v2",true));
    }
}
