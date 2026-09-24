package harness;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Translate the compact project contract to execution settings, never scheduling jobs. */
final class WorkflowRuntime {
    private WorkflowRuntime() { }

    static Object unwrap(Object parsed) throws IOException {
        if (!(parsed instanceof Map<?, ?> doc) || !doc.containsKey("schema_version")) return parsed;
        if (!Integer.valueOf(2).equals(doc.get("schema_version")) && !Integer.valueOf(3).equals(doc.get("schema_version")))
            throw new IOException("Unsupported workflow schema; explicitly regenerate project artifacts for schema 2 or 3");
        if (!(doc.get("bindings") instanceof Map<?, ?> bindings)
                || !(bindings.get("controller") instanceof Map<?, ?> controller)
                || !(doc.get("execution") instanceof Map<?, ?> execution)
                || !(doc.get("recovery_policy") instanceof Map<?, ?> recovery))
            throw new IOException("Incomplete compact workflow bindings/policy");
        Map<Object, Object> runtime = new LinkedHashMap<>(bindings);
        Map<Object, Object> settings = new LinkedHashMap<>(controller);
        settings.put("observation_attempts", execution.get("observation_attempts"));
        settings.put("observation_interval_seconds", execution.get("observation_interval_seconds"));
        Map<Object, Object> sources = new LinkedHashMap<>();
        for (var entry : recovery.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> policy)) throw new IOException("Invalid recovery policy");
            sources.put(entry.getKey(), policy.get("release_source"));
        }
        settings.put("release_sources", sources);
        if (doc.get("candidate_repair") instanceof Map<?, ?> repairs) {
            Map<Object,Object> jobs=new LinkedHashMap<>((Map<?,?>)settings.get("jobs"));
            for (var entry: repairs.entrySet()) {
                Map<?,?> rule=(Map<?,?>)entry.getValue();
                jobs.put("diagnose_"+entry.getKey(), rule.get("diagnose_job_name"));
                jobs.put("restart_"+entry.getKey(), rule.get("restart_job_name"));
            }
            settings.put("jobs",jobs);
        }
        runtime.put("controller", settings);
        return runtime;
    }
}
