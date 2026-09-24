package harness;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConventionalPolicyTest {
    static final ProjectTelemetryProvider.Measurement GOOD = new ProjectTelemetryProvider.Measurement("fresh", "ready", 0, 20, 1);
    static final ProjectTelemetryProvider.Measurement BAD = new ProjectTelemetryProvider.Measurement("fresh", "ready", 1, 20, 1);
    static final ProjectTelemetryProvider.Measurement UNKNOWN = new ProjectTelemetryProvider.Measurement("unavailable", "ready", 0, 0, 0);
    static class Fake implements ConventionalPolicy.IO {
        List<String> executions = new ArrayList<>();
        Map<String, String> statuses = new HashMap<>();
        Map<String, List<ProjectTelemetryProvider.Measurement>> samples = new HashMap<>();
        String reconciled = "unknown";
        long now;
        long productionDuration = 42;
        public EntityExecution.Result execute(String e, int a) { executions.add(e+":"+a); return new EntityExecution.Result(statuses.getOrDefault(e+":"+a,"success"), e.equals("production")?productionDuration:42, e+"-"+a, 1,"url"); }
        public EntityExecution.Result reconcile(String e,int a,int r) { return new EntityExecution.Result(reconciled,42,e+"-"+a,1,"url"); }
        public ProjectTelemetryProvider.Measurement measure(String e,int a,int r,String id) { var list=samples.getOrDefault(e,List.of(GOOD)); return list.get(Math.min(r-1,list.size()-1)); }
        public void event(String n,Map<String,?> f) { }
        public void sleep(long ms) { now+=ms; }
        ConventionalPolicy policy(boolean known) { return new ConventionalPolicy(new ConventionalPolicy.Settings(1,5,4,5,100,2,3,5,1800000,.05,500,
            Set.of("build","test","security","staging","production"),Set.of("failure","telemetry_block","telemetry_unknown","maintenance_violation")),this,()->now,known); }
    }
    @Test void healthy() throws Exception { var f=new Fake(); assertEquals("achieved",f.policy(true).run().outcome()); assertEquals(5,f.executions.size()); }
    @Test void transientRetryAndDeterministicStop() throws Exception {
        var f=new Fake();f.statuses.put("test:1","transient_failure");assertEquals("achieved",f.policy(true).run().outcome());assertTrue(f.executions.contains("test:2"));
        var g=new Fake();g.statuses.put("build:1","failure");assertEquals("stopped",g.policy(true).run().outcome());assertEquals(List.of("build:1"),g.executions);
    }
    @Test void stagingBlocksAndProductionRecovers() throws Exception {
        var f=new Fake();f.samples.put("staging",List.of(BAD));assertEquals("not_attempted",f.policy(true).run().recovery());assertFalse(f.executions.contains("production:1"));
        var g=new Fake();g.samples.put("production",List.of(BAD));var o=g.policy(true).run();assertEquals("stopped",o.outcome());assertEquals("restored",o.recovery());assertEquals("rollback:1",g.executions.getLast());
    }
    @Test void transientHealthAndConsecutiveRequirement() throws Exception {
        var f=new Fake();f.samples.put("production",List.of(BAD,GOOD,GOOD));assertEquals("achieved",f.policy(true).run().outcome());
        var g=new Fake();g.samples.put("production",List.of(GOOD,BAD,GOOD,BAD));assertEquals("restored",g.policy(true).run().recovery());
    }
    @Test void uncertaintyNeverRedispatchesOrRollsBack() throws Exception {
        var f=new Fake();f.statuses.put("production:1","unknown");var o=f.policy(true).run();assertEquals("unknown",o.outcome());assertEquals("unresolved",o.recovery());assertEquals(5,f.executions.size());
    }
    @Test void confirmedReconciliationCanContinue() throws Exception {
        var f=new Fake();f.statuses.put("production:1","unknown");f.reconciled="success";assertEquals("achieved",f.policy(true).run().outcome());assertEquals(5,f.executions.size());
    }
    @Test void recoveryNeedsKnownGoodAndHealthyEvidence() throws Exception {
        var f=new Fake();f.samples.put("production",List.of(BAD));assertEquals("not_attempted",f.policy(false).run().recovery());
        var g=new Fake();g.samples.put("production",List.of(BAD));g.samples.put("rollback",List.of(UNKNOWN));assertEquals("unverified",g.policy(true).run().recovery());
    }
    @Test void durationViolationRecoversAndRollbackIsNeverRetried() throws Exception {
        var f=new Fake();f.productionDuration=1800001;f.statuses.put("rollback:1","transient_failure");assertEquals("failed",f.policy(true).run().recovery());assertFalse(f.executions.contains("rollback:2"));
    }
    @Test void commonEventsNormalizeBothMechanisms() {
        var b=StructuredEventLogger.normalized(Map.of("event","bdi_recovery_decision","entity","rollback"),"bdi","a");
        var c=StructuredEventLogger.normalized(Map.of("event","conventional_recovery_decision","entity","rollback"),"conventional","b");
        assertEquals(b.get("event"),c.get("event"));assertEquals("recovery_started",b.get("event"));assertEquals("conventional",c.get("mechanism"));
    }

    @Test void exhaustedProductionTransientFailureUsesFailureRecoveryTrigger() throws Exception {
        var f=new Fake();f.statuses.put("production:1","transient_failure");f.statuses.put("production:2","transient_failure");
        assertEquals("restored",f.policy(true).run().recovery());assertEquals("rollback:1",f.executions.getLast());
    }
}
