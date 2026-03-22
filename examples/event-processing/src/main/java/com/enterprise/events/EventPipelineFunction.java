package com.enterprise.events;

import com.kubefn.api.*;

import java.util.*;

/**
 * Orchestrates the full event processing pipeline: ingest -> enrich -> anomaly detect -> alert.
 * All stages execute in-memory via HeapExchange. Reports per-stage timing and
 * a complete audit trail of the event lifecycle.
 */
@FnRoute(path = "/events/process", methods = {"POST"})
@FnGroup("event-processor")
public class EventPipelineFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long pipelineStart = System.nanoTime();
        List<Map<String, Object>> stageTiming = new ArrayList<>();

        // Stage 1: Ingest
        long stageStart = System.nanoTime();
        var ingestResponse = ctx.getFunction(EventIngestFunction.class).handle(request);
        stageTiming.add(stageEntry("ingest", System.nanoTime() - stageStart, ingestResponse.statusCode()));

        if (ingestResponse.statusCode() != 200) {
            return buildPipelineError("ingest", stageTiming, pipelineStart, ingestResponse);
        }

        // Stage 2: Enrich
        stageStart = System.nanoTime();
        var enrichResponse = ctx.getFunction(EventEnrichFunction.class).handle(request);
        stageTiming.add(stageEntry("enrich", System.nanoTime() - stageStart, enrichResponse.statusCode()));

        // Stage 3: Anomaly Detection
        stageStart = System.nanoTime();
        var anomalyResponse = ctx.getFunction(AnomalyDetectionFunction.class).handle(request);
        stageTiming.add(stageEntry("anomaly_detection", System.nanoTime() - stageStart, anomalyResponse.statusCode()));

        // Stage 4: Alert Generation
        stageStart = System.nanoTime();
        var alertResponse = ctx.getFunction(AlertGeneratorFunction.class).handle(request);
        stageTiming.add(stageEntry("alert_generation", System.nanoTime() - stageStart, alertResponse.statusCode()));

        // Assemble pipeline result from heap
        var ingested = ctx.heap().get("event:ingested", Map.class).orElse(Map.of());
        var enriched = ctx.heap().get("event:enriched", Map.class).orElse(Map.of());
        var anomalyResult = ctx.heap().get("event:anomaly-result", Map.class).orElse(Map.of());
        var alertResult = ctx.heap().get("event:alert-result", Map.class).orElse(Map.of());

        long totalNanos = System.nanoTime() - pipelineStart;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("eventId", ingested.get("eventId"));
        response.put("deviceId", ingested.get("deviceId"));
        response.put("eventType", ingested.get("eventType"));
        response.put("value", ingested.get("value"));
        response.put("unit", ingested.get("unit"));
        response.put("deviceKnown", enriched.get("deviceKnown"));
        response.put("dataReliability", enriched.get("dataReliability"));
        response.put("anomalyDetected", anomalyResult.get("anomalyDetected"));
        response.put("severity", anomalyResult.get("severity"));
        response.put("anomalyCount", anomalyResult.get("anomalyCount"));
        response.put("alertGenerated", alertResult.get("alertGenerated"));

        if (Boolean.TRUE.equals(alertResult.get("alertGenerated"))) {
            Map<String, Object> alert = (Map<String, Object>) alertResult.get("alert");
            response.put("alertId", alert.get("alertId"));
            response.put("alertChannels", alert.get("channels"));
            response.put("alertSummary", alert.get("summary"));
        }

        response.put("_meta", Map.of(
                "pipelineStages", 4,
                "stageTiming", stageTiming,
                "totalTimeMs", String.format("%.3f", totalNanos / 1_000_000.0),
                "totalTimeNanos", totalNanos,
                "heapKeysUsed", ctx.heap().keys().size(),
                "zeroCopy", true,
                "note", "Full IoT event pipeline processed in-memory. Zero serialization between stages."
        ));

        return KubeFnResponse.ok(response);
    }

    private KubeFnResponse buildPipelineError(String failedStage,
                                               List<Map<String, Object>> stageTiming,
                                               long pipelineStart,
                                               KubeFnResponse stageResponse) {
        long totalNanos = System.nanoTime() - pipelineStart;

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pipelineStatus", "failed");
        error.put("failedAt", failedStage);
        error.put("stageResponse", stageResponse.body());
        error.put("_meta", Map.of(
                "stageTiming", stageTiming,
                "totalTimeMs", String.format("%.3f", totalNanos / 1_000_000.0),
                "abortedAt", failedStage
        ));

        return KubeFnResponse.status(stageResponse.statusCode()).body(error);
    }

    private Map<String, Object> stageEntry(String stage, long nanos, int status) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stage", stage);
        entry.put("durationNanos", nanos);
        entry.put("durationMs", String.format("%.3f", nanos / 1_000_000.0));
        entry.put("status", status);
        return entry;
    }
}
