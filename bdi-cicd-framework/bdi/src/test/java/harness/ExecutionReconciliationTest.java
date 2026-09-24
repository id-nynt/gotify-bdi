package harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionReconciliationTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();

    private GitHubEntityExecution adapter(HttpServer server) throws Exception {
        return new GitHubEntityExecution(ControllerProjectConfig.load(Path.of("fixtures/controller-workflow.yaml")),
            new StructuredEventLogger(null), URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
            "example/repository", "test-token", "main", "a".repeat(40), "campaign-test", Duration.ofMillis(1), Duration.ofMillis(100));
    }
    private static void respond(HttpExchange exchange, int code, String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);
        exchange.getResponseBody().write(bytes);exchange.close();
    }

    @Test void lostAcknowledgementIsFoundByExecutionIdentityWithoutAnotherPost() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var posts=new AtomicInteger();var id=new AtomicReference<String>();
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/dispatches",e->{
            posts.incrementAndGet();id.set(JSON.readTree(e.getRequestBody()).path("inputs").path("execution_id").asText());
            respond(e,503,"lost acknowledgement");
        });
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/runs",e->
            respond(e,200,"{\"workflow_runs\":[{\"id\":321,\"display_title\":\"bdi-"+id.get()+"\"}]}"));
        server.createContext("/repos/example/repository/actions/runs/321/jobs",e->respond(e,200,
            "{\"jobs\":[{\"name\":\"Build entity\",\"status\":\"completed\",\"conclusion\":\"success\"}]}"));
        server.createContext("/repos/example/repository/actions/runs/321",e->respond(e,200,"{\"status\":\"completed\"}"));
        server.start();
        try {
            Path state=directory.resolve("pending.json");var first=adapter(server);first.useStateFile(state);
            assertEquals("unknown",first.execute("build",1).status());assertTrue(Files.exists(state));
            // Simulate restart: unresolved durable state prevents a fresh POST.
            var restarted=adapter(server);restarted.useStateFile(state);
            assertEquals("unknown",restarted.execute("build",2).status());
            assertEquals("success",restarted.reconcile("build",1).status());
            assertEquals(1,posts.get());assertFalse(Files.exists(state));
        } finally {server.stop(0);}
    }

    @Test void absentDiscoveryNeverAuthorizesRedispatch() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var posts=new AtomicInteger();
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/dispatches",e->{posts.incrementAndGet();respond(e,503,"unknown");});
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/runs",e->respond(e,200,"{\"workflow_runs\":[]}"));
        server.start();
        try {
            var adapter=adapter(server);adapter.useStateFile(directory.resolve("pending.json"));
            assertEquals("unknown",adapter.execute("build",1).status());
            assertEquals("unknown",adapter.reconcile("build",1).status());
            assertEquals("unknown",adapter.execute("build",2).status());
            assertEquals(1,posts.get());
        } finally {server.stop(0);}
    }

    @Test void pollingFailureRetainsAcknowledgedRunForReconciliation() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var polls=new AtomicInteger();var posts=new AtomicInteger();
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/dispatches",e->{posts.incrementAndGet();respond(e,200,"{\"workflow_run_id\":12}");});
        server.createContext("/repos/example/repository/actions/runs/12/jobs",e->respond(e,200,
            "{\"jobs\":[{\"name\":\"Build entity\",\"status\":\"completed\",\"conclusion\":\"failure\"}]}"));
        server.createContext("/repos/example/repository/actions/runs/12",e->{if(polls.incrementAndGet()==1) respond(e,503,"unavailable");else respond(e,200,"{\"status\":\"completed\"}");});
        server.start();
        try {
            var adapter=adapter(server);adapter.useStateFile(directory.resolve("pending.json"));
            assertEquals("unknown",adapter.execute("build",1).status());
            assertEquals("failure",adapter.reconcile("build",1).status());
            assertEquals(1,posts.get());
        } finally {server.stop(0);}
    }

    @Test void ambiguousExecutionIdentityRemainsUnresolved() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var posts=new AtomicInteger();var id=new AtomicReference<String>();
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/dispatches",e->{
            posts.incrementAndGet();id.set(JSON.readTree(e.getRequestBody()).path("inputs").path("execution_id").asText());
            respond(e,503,"lost acknowledgement");
        });
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/runs",e->{
            var body=JSON.createObjectNode();var runs=body.putArray("workflow_runs");
            runs.addObject().put("id",1).put("display_title","bdi-"+id.get());
            runs.addObject().put("id",2).put("display_title","bdi-"+id.get());
            respond(e,200,body.toString());
        });
        server.start();
        try {
            var adapter=adapter(server);Path state=directory.resolve("ambiguous.json");adapter.useStateFile(state);
            assertEquals("unknown",adapter.execute("build",1).status());
            assertEquals("unknown",adapter.reconcile("build",1).status());
            assertEquals("unknown",adapter.execute("build",2).status());
            assertEquals(1,posts.get());assertTrue(Files.exists(state));
        } finally {server.stop(0);}
    }

    @Test void explicitDispatchRejectionsSettleFailureAndPermitNextSelectedAttempt() throws Exception {
        for (int status : new int[]{401, 403, 404, 422}) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            var posts=new AtomicInteger();
            server.createContext("/", e->{posts.incrementAndGet();respond(e,status,"rejected");});
            server.start();
            try {
                Path state=directory.resolve("rejected-"+status+".json");
                var adapter=adapter(server);adapter.useStateFile(state);
                var result=adapter.execute("build",1);
                assertEquals("dispatch_rejected",result.status());assertEquals(0,result.githubRunId());
                assertFalse(Files.exists(state));
                assertEquals("dispatch_rejected",adapter.execute("build",2).status());
                assertEquals(2,posts.get());
            } finally {server.stop(0);}
        }
    }

    @Test void forbiddenPollingIsStillUncertainAfterAcknowledgement() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/dispatches",
            e->respond(e,200,"{\"workflow_run_id\":12}"));
        server.createContext("/repos/example/repository/actions/runs/12",e->respond(e,403,"forbidden"));
        server.start();
        try {
            Path state=directory.resolve("polling.json");var adapter=adapter(server);adapter.useStateFile(state);
            assertEquals("unknown",adapter.execute("build",1).status());
            assertEquals(12,JSON.readTree(Files.readString(state)).path("run_id").asInt());
        } finally {server.stop(0);}
    }

    private Path legacyEvidence(Path state, String reason, boolean acknowledged) throws Exception {
        var pending=JSON.readTree(Files.readString(state));
        Path evidence=Files.createDirectory(directory.resolve("evidence"));
        var receipt=JSON.createObjectNode().put("mode","github");
        for (String key:new String[]{"campaign_id","release_sha","repository"}) receipt.set(key,pending.path(key));
        Files.writeString(evidence.resolve("controller-result.json"),receipt.toString());
        var intent=JSON.createObjectNode().put("event","dispatch_intent");
        for (String key:new String[]{"campaign_id","entity","attempt","execution_id","release_sha"}) intent.set(key,pending.path(key));
        var rejected=JSON.createObjectNode().put("event","execution_uncertain")
            .put("execution_id",pending.path("execution_id").asText()).put("reason",reason);
        String journal=intent+"\n"+rejected+"\n";
        if (acknowledged) journal+=JSON.createObjectNode().put("event","dispatch_acknowledged")
            .put("execution_id",pending.path("execution_id").asText())+"\n";
        Files.writeString(evidence.resolve("controller-journal.jsonl"),journal);
        return evidence;
    }

    @Test void legacyRejectionRecoveryArchivesEvidenceWithoutSendingAnotherRequest() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/",e->{calls.incrementAndGet();respond(e,503,"unknown");});server.start();
        try {
            Path state=directory.resolve("legacy.json");var adapter=adapter(server);adapter.useStateFile(state);
            adapter.execute("build",1);
            Path evidence=legacyEvidence(state,"GitHub dispatch returned HTTP 403: forbidden",false);
            Path archive=directory.resolve("archive");
            assertEquals("dispatch_rejected",adapter.reconcileRejectedDispatch(evidence,archive).status());
            assertFalse(Files.exists(state));assertEquals(1,calls.get());
            assertTrue(Files.exists(archive.resolve("rejected-dispatch-pending.json")));
            assertEquals(Files.readString(evidence.resolve("controller-journal.jsonl")),
                Files.readString(archive.resolve("rejected-dispatch-journal.jsonl")));
        } finally {server.stop(0);}
    }

    @Test void legacyRecoveryRejectsUncertainMismatchedAndAcknowledgedEvidence() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->respond(e,503,"unknown"));server.start();
        try {
            Path state=directory.resolve("legacy.json");var adapter=adapter(server);adapter.useStateFile(state);
            adapter.execute("build",1);
            Path evidence=legacyEvidence(state,"GitHub dispatch returned HTTP 503: unknown",false);
            Path journal=evidence.resolve("controller-journal.jsonl");
            String original=Files.readString(journal);
            assertThrows(IllegalStateException.class,()->adapter.reconcileRejectedDispatch(evidence,directory.resolve("archive")));
            Files.writeString(journal,original.replace("503", "403").replace("campaign-test","wrong-campaign"));
            assertThrows(IllegalStateException.class,()->adapter.reconcileRejectedDispatch(evidence,directory.resolve("archive")));
            String id=JSON.readTree(Files.readString(state)).path("execution_id").asText();
            Files.writeString(journal,original.replace("503","403")+JSON.createObjectNode()
                .put("event","dispatch_acknowledged").put("execution_id",id)+"\n");
            assertThrows(IllegalStateException.class,()->adapter.reconcileRejectedDispatch(evidence,directory.resolve("archive")));
            assertTrue(Files.exists(state));
        } finally {server.stop(0);}
    }

    @Test void persistedRejectionSettlesAfterRestartWithoutRemoteLookup() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/",e->{calls.incrementAndGet();respond(e,503,"unknown");});server.start();
        try {
            Path state=directory.resolve("restart.json");var adapter=adapter(server);adapter.useStateFile(state);
            adapter.execute("build",1);
            var record=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(Files.readString(state));
            record.put("dispatch_rejected_http_status",403);Files.writeString(state,record.toString());
            var restarted=adapter(server);restarted.useStateFile(state);
            assertEquals("dispatch_rejected",restarted.reconcilePending().status());
            assertFalse(Files.exists(state));assertEquals(1,calls.get());
        } finally {server.stop(0);}
    }

    @Test void onlyFailedTransientStepClassifiesAJobAsRetryable() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var stepConclusion=new AtomicReference<>("skipped");
        server.createContext("/repos/example/repository/actions/workflows/entity-execution.yml/dispatches",
            e->respond(e,200,"{\"workflow_run_id\":12}"));
        server.createContext("/repos/example/repository/actions/runs/12/jobs",e->respond(e,200,
            "{\"jobs\":[{\"name\":\"Build entity\",\"status\":\"completed\",\"conclusion\":\"failure\","
            +"\"steps\":[{\"name\":\"Controlled transient failure\",\"conclusion\":\""+stepConclusion.get()+"\"}]}]}"));
        server.createContext("/repos/example/repository/actions/runs/12",e->respond(e,200,"{\"status\":\"completed\"}"));
        server.start();
        try {
            var adapter=adapter(server);
            assertEquals("failure",adapter.execute("build",1).status());
            stepConclusion.set("failure");
            assertEquals("transient_failure",adapter.execute("build",2).status());
        } finally {server.stop(0);}
    }
    @Test void invalidTokenIsRejectedBeforeAnyNetworkRequest() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/",e->{calls.incrementAndGet();respond(e,200,"{}");});server.start();
        try {
            String secret="secret" + (char)22;
            var error=assertThrows(IllegalArgumentException.class,()->new GitHubEntityExecution(
                ControllerProjectConfig.load(Path.of("fixtures/controller-workflow.yaml")), new StructuredEventLogger(null),
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()), "example/repository", secret,
                "main", "a".repeat(40), "campaign-test", Duration.ofMillis(1), Duration.ofMillis(100)));
            assertFalse(error.getMessage().contains(secret));assertEquals(0,calls.get());
        } finally {server.stop(0);}
    }

    @Test void legacyInvalidHeaderRecoveryRequiresLocalFailureProof() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/",e->{calls.incrementAndGet();respond(e,503,"unknown");});server.start();
        try {
            Path state=directory.resolve("header.json");var adapter=adapter(server);adapter.useStateFile(state);
            adapter.execute("build",1);
            Path evidence=legacyEvidence(state,"invalid header value: \"Bearer " + (char)22 + "\"",false);
            Path journal=evidence.resolve("controller-journal.jsonl");String valid=Files.readString(journal).replace("\\u0016", String.valueOf((char)22));
            Files.writeString(journal,valid.replace("Bearer", "Other"));
            assertThrows(IllegalStateException.class,()->adapter.reconcileRejectedDispatch(evidence,directory.resolve("bad")));
            assertTrue(Files.exists(state));
            Files.writeString(journal,valid);
            assertEquals("dispatch_rejected",adapter.reconcileRejectedDispatch(evidence,directory.resolve("archive-header")).status());
            assertFalse(Files.exists(state));assertEquals(1,calls.get());
        } finally {server.stop(0);}
    }

    @Test void structuredJournalEscapesAllControlCharacters() throws Exception {
        Path log=directory.resolve("control.jsonl");
        String text="prefix" + (char)22 + "\t\n\r";
        new StructuredEventLogger(log).event("test",null,java.util.Map.of("reason",text));
        var lines=Files.readAllLines(log);
        assertEquals(1,lines.size());
        assertEquals(text,JSON.readTree(lines.get(0)).path("reason").asText());
    }

}
