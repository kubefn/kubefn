package com.enterprise.clinical;

import com.kubefn.api.*;

import java.util.*;

/**
 * Orchestrates the full clinical assessment pipeline:
 * vitals ingest -> lab results -> risk scoring -> guideline check -> alert decision.
 * All stages execute in-memory via HeapExchange for real-time clinical decision support.
 */
@FnRoute(path = "/clinical/assess", methods = {"POST"})
@FnGroup("clinical-engine")
public class ClinicalPipelineFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long pipelineStart = System.nanoTime();
        List<Map<String, Object>> stageTiming = new ArrayList<>();

        // Stage 1: Ingest Vitals
        long stageStart = System.nanoTime();
        var vitalsResponse = ctx.getFunction(VitalsIngestFunction.class).handle(request);
        stageTiming.add(stageEntry("vitals_ingest", System.nanoTime() - stageStart, vitalsResponse.statusCode()));

        if (vitalsResponse.statusCode() != 200) {
            return buildPipelineError("vitals_ingest", stageTiming, pipelineStart, vitalsResponse);
        }

        // Stage 2: Process Lab Results
        stageStart = System.nanoTime();
        var labsResponse = ctx.getFunction(LabResultsFunction.class).handle(request);
        stageTiming.add(stageEntry("lab_results", System.nanoTime() - stageStart, labsResponse.statusCode()));

        // Stage 3: Risk Scoring
        stageStart = System.nanoTime();
        var riskResponse = ctx.getFunction(RiskScoringFunction.class).handle(request);
        stageTiming.add(stageEntry("risk_scoring", System.nanoTime() - stageStart, riskResponse.statusCode()));

        // Stage 4: Guideline Check
        stageStart = System.nanoTime();
        var guidelineResponse = ctx.getFunction(GuidelineCheckFunction.class).handle(request);
        stageTiming.add(stageEntry("guideline_check", System.nanoTime() - stageStart, guidelineResponse.statusCode()));

        // Stage 5: Alert Decision
        stageStart = System.nanoTime();
        var alertResponse = ctx.getFunction(AlertDecisionFunction.class).handle(request);
        stageTiming.add(stageEntry("alert_decision", System.nanoTime() - stageStart, alertResponse.statusCode()));

        // Assemble comprehensive clinical assessment from heap
        var vitalsData = ctx.heap().get("clinical:vitals", Map.class).orElse(Map.of());
        var labsData = ctx.heap().get("clinical:labs", Map.class).orElse(Map.of());
        var riskScores = ctx.heap().get("clinical:risk-scores", Map.class).orElse(Map.of());
        var guidelineCheck = ctx.heap().get("clinical:guideline-check", Map.class).orElse(Map.of());
        var alertDecision = ctx.heap().get("clinical:alert-decision", Map.class).orElse(Map.of());

        long totalNanos = System.nanoTime() - pipelineStart;

        // Build clinical summary
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("patientId", vitalsData.get("patientId"));
        response.put("encounterId", vitalsData.get("encounterId"));

        // Clinical snapshot
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("vitals", vitalsData.get("vitals"));
        snapshot.put("vitalCount", vitalsData.get("vitalCount"));
        snapshot.put("labSummary", Map.of(
                "testCount", labsData.getOrDefault("testCount", 0),
                "criticalCount", labsData.getOrDefault("criticalCount", 0),
                "abnormalCount", labsData.getOrDefault("abnormalCount", 0),
                "overallLabStatus", labsData.getOrDefault("overallStatus", "unknown")
        ));
        response.put("clinicalSnapshot", snapshot);

        // Risk assessment
        Map<String, Object> riskAssessment = new LinkedHashMap<>();
        riskAssessment.put("overallRiskLevel", riskScores.get("overallRiskLevel"));
        riskAssessment.put("qsofa", riskScores.get("qsofa"));
        riskAssessment.put("mews", riskScores.get("mews"));
        riskAssessment.put("sepsisRisk", riskScores.get("sepsisRisk"));
        response.put("riskAssessment", riskAssessment);

        // Guideline compliance
        response.put("guidelineCompliance", Map.of(
                "violationCount", guidelineCheck.getOrDefault("violationCount", 0),
                "recommendationCount", guidelineCheck.getOrDefault("recommendationCount", 0),
                "highestPriority", guidelineCheck.getOrDefault("highestPriority", "low"),
                "recommendations", guidelineCheck.getOrDefault("recommendations", List.of())
        ));

        // Alert outcome
        response.put("alertOutcome", Map.of(
                "shouldAlert", alertDecision.getOrDefault("shouldAlert", false),
                "alertScore", alertDecision.getOrDefault("alertScore", 0),
                "alert", alertDecision.getOrDefault("alert", null),
                "suppressionReason", alertDecision.getOrDefault("suppressionReason", null)
        ));

        response.put("dataQuality", vitalsData.getOrDefault("dataQuality", "unknown"));

        response.put("_meta", Map.of(
                "pipelineStages", 5,
                "stageTiming", stageTiming,
                "totalTimeMs", String.format("%.3f", totalNanos / 1_000_000.0),
                "totalTimeNanos", totalNanos,
                "heapKeysUsed", ctx.heap().keys().size(),
                "zeroCopy", true,
                "note", "Full clinical assessment pipeline executed in-memory. " +
                        "5 stages, zero HTTP calls, real-time decision support."
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
