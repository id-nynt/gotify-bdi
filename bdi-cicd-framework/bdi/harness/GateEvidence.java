package harness;

import java.util.Map;

/** Inputs to the Jason promotion decision; this class does not choose the final gate result. */
public record GateEvidence(String workflow, String telemetry, String reason) {
    public static GateEvidence from(ProjectConfig project, Map<String, GitHubRunObserver.Job> jobs,
                                    ProjectTelemetryProvider.Assessment assessment) {
        String workflow = "passed";
        for (String role : project.jobs().keySet()) {
            if (role.equals(project.promotionGate().before())) continue;
            GitHubRunObserver.Job job = jobs.get(role);
            if (job != null && job.completed() && !job.succeeded()) {
                workflow = "failed";
                break;
            }
            if (job == null || !job.completed()) workflow = "unknown";
        }
        return new GateEvidence(workflow, assessment.decision(), assessment.reason());
    }
}
