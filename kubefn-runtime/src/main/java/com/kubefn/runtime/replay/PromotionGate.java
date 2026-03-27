package com.kubefn.runtime.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubefn.runtime.routing.FunctionRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Evidence-based promotion gate for hot-swap.
 *
 * <p>Before a new function revision goes live, the PromotionGate:
 * <ol>
 *   <li>Collects recent VALUE captures for that function</li>
 *   <li>Replays them against the NEW revision</li>
 *   <li>Checks output diff against promotion policy</li>
 *   <li>Returns PROMOTE / WARN / BLOCK verdict</li>
 * </ol>
 *
 * <p>Modes:
 * <ul>
 *   <li><b>ADVISORY</b> — log verdict, never block (safe default)</li>
 *   <li><b>ENFORCING</b> — block promotion on policy violation</li>
 * </ul>
 *
 * <p>This is the "prove it's safe" step that turns hot-swap from
 * YOLO reload into evidence-based live evolution.
 */
public class PromotionGate {
    private static final Logger log = LoggerFactory.getLogger(PromotionGate.class);

    private final CaptureStore captureStore;
    private final FunctionRouter router;
    private final ObjectMapper objectMapper;
    private GateMode mode = GateMode.ADVISORY;
    private int minCapturesRequired = 5;
    private double maxFailureRate = 0.0;       // 0% failures allowed
    private double maxDivergenceRate = 0.05;   // 5% output divergence allowed
    private double maxLatencyIncrease = 0.20;  // 20% latency increase allowed

    public PromotionGate(CaptureStore captureStore, FunctionRouter router, ObjectMapper objectMapper) {
        this.captureStore = captureStore;
        this.router = router;
        this.objectMapper = objectMapper;
    }

    /**
     * Validate a function revision before promotion.
     *
     * @param functionName the function being hot-swapped
     * @param newRevisionId the new revision ID
     * @return verdict with details
     */
    public Verdict validate(String functionName, String newRevisionId) {
        // Collect VALUE captures for this function
        var captures = captureStore.find(
                c -> c.functionName().equals(functionName)
                        && c.level() == InvocationCapture.CaptureLevel.VALUE
                        && c.inputSnapshot() != null,
                200 // max captures to replay
        );

        if (captures.size() < minCapturesRequired) {
            String msg = String.format(
                    "Insufficient captures for %s: %d < %d required. Allowing promotion without validation.",
                    functionName, captures.size(), minCapturesRequired);
            log.warn(msg);
            return new Verdict(Decision.PROMOTE, msg, null, Instant.now());
        }

        // Replay all captures against new code
        var executor = new ReplayExecutor(router, objectMapper);
        var batch = executor.replayBatch(captures);

        // Evaluate against policy
        List<String> violations = new ArrayList<>();

        // Check failure rate
        double failureRate = batch.total() > 0 ? (double) batch.failed() / batch.total() : 0;
        if (failureRate > maxFailureRate) {
            violations.add(String.format("Failure rate %.1f%% exceeds limit %.1f%%",
                    failureRate * 100, maxFailureRate * 100));
        }

        // Check divergence rate
        double divergenceRate = batch.total() > 0 ? (double) batch.diverged() / batch.total() : 0;
        if (divergenceRate > maxDivergenceRate) {
            violations.add(String.format("Output divergence %.1f%% exceeds limit %.1f%%",
                    divergenceRate * 100, maxDivergenceRate * 100));
        }

        // Check latency regression
        double avgOriginalNanos = batch.results().stream()
                .filter(r -> r.originalDurationNanos() > 0)
                .mapToLong(ReplayExecutor.ReplayResult::originalDurationNanos)
                .average().orElse(0);
        double avgReplayNanos = batch.results().stream()
                .filter(r -> r.replayDurationNanos() > 0)
                .mapToLong(ReplayExecutor.ReplayResult::replayDurationNanos)
                .average().orElse(0);

        if (avgOriginalNanos > 0 && avgReplayNanos > 0) {
            double latencyIncrease = (avgReplayNanos / avgOriginalNanos) - 1.0;
            if (latencyIncrease > maxLatencyIncrease) {
                violations.add(String.format("Latency increase %.1f%% exceeds limit %.1f%%",
                        latencyIncrease * 100, maxLatencyIncrease * 100));
            }
        }

        // Check errors
        if (batch.errors() > 0) {
            violations.add(String.format("%d replay errors (could not execute)", batch.errors()));
        }

        // Determine verdict
        Decision decision;
        String summary;

        if (violations.isEmpty()) {
            decision = Decision.PROMOTE;
            summary = String.format("SAFE: %d/%d passed, 0 violations. Replayed %s against %s.",
                    batch.passed(), batch.total(), functionName, newRevisionId);
            log.info("PromotionGate: {} — {}", decision, summary);
        } else if (mode == GateMode.ADVISORY) {
            decision = Decision.WARN;
            summary = String.format("WARNING: %d violation(s) detected but gate is ADVISORY. %s",
                    violations.size(), String.join("; ", violations));
            log.warn("PromotionGate: {} — {}", decision, summary);
        } else {
            decision = Decision.BLOCK;
            summary = String.format("BLOCKED: %d violation(s). %s",
                    violations.size(), String.join("; ", violations));
            log.error("PromotionGate: {} — {}", decision, summary);
        }

        return new Verdict(decision, summary, batch.toMap(), Instant.now());
    }

    // ── Configuration ──

    public void setMode(GateMode mode) { this.mode = mode; }
    public GateMode getMode() { return mode; }
    public void setMinCapturesRequired(int n) { this.minCapturesRequired = n; }
    public void setMaxFailureRate(double rate) { this.maxFailureRate = rate; }
    public void setMaxDivergenceRate(double rate) { this.maxDivergenceRate = rate; }
    public void setMaxLatencyIncrease(double rate) { this.maxLatencyIncrease = rate; }

    public Map<String, Object> status() {
        return Map.of(
                "mode", mode.name(),
                "minCapturesRequired", minCapturesRequired,
                "maxFailureRate", String.format("%.1f%%", maxFailureRate * 100),
                "maxDivergenceRate", String.format("%.1f%%", maxDivergenceRate * 100),
                "maxLatencyIncrease", String.format("%.1f%%", maxLatencyIncrease * 100)
        );
    }

    // ── Types ──

    public enum GateMode {
        /** Log verdict, never block. Safe default for adoption. */
        ADVISORY,
        /** Block promotion on policy violation. Production mode. */
        ENFORCING
    }

    public enum Decision {
        /** Safe to promote — all checks passed */
        PROMOTE,
        /** Violations detected but gate is advisory — promote with warnings */
        WARN,
        /** Violations detected and gate is enforcing — promotion blocked */
        BLOCK
    }

    public record Verdict(
            Decision decision,
            String summary,
            Map<String, Object> replayResults,
            Instant evaluatedAt
    ) {
        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("decision", decision.name());
            map.put("summary", summary);
            map.put("evaluatedAt", evaluatedAt.toString());
            map.put("replay", replayResults);
            return map;
        }
    }
}
