package com.example.patterns;

import com.kubefn.api.FnContextAware;
import com.kubefn.api.FnContext;
import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnRequest;
import com.kubefn.api.KubeFnResponse;
import com.kubefn.api.FnRoute;
import com.kubefn.api.FnGroup;
import com.kubefn.api.HeapExchange;

import com.kubefn.contracts.AuthContext;
import com.kubefn.contracts.PricingResult;
import com.kubefn.contracts.FraudScore;
import com.kubefn.contracts.HeapKeys;


/**
 * PIPELINE STEP 4: Fraud Scoring
 * ================================
 * Reads AuthContext AND PricingResult from heap, computes a fraud score,
 * and publishes a FraudScore.
 *
 * THIS IS A MULTI-DEPENDENCY STEP.
 * It reads TWO objects from heap — AuthContext (from AuthStep) and
 * PricingResult (from PricingStep). The orchestrator must ensure both
 * steps have run before calling FraudStep.
 *
 * DATA FLOW:
 *   AuthStep    --publishes--> AuthContext    --\
 *                                                +--> FraudStep --publishes--> FraudScore
 *   PricingStep --publishes--> PricingResult --/
 *
 * WHY fraud scoring reads multiple heap entries:
 * Fraud detection requires cross-cutting data. A $10 purchase by a premium
 * user is low risk. A $10,000 purchase by a new user is high risk.
 * The combination of auth + pricing determines the risk score.
 */
@FnRoute(path = "/checkout/fraud", methods = {"POST"})
@FnGroup("patterns")
public class FraudStep implements KubeFnHandler, FnContextAware {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        HeapExchange heap = ctx.heap();

        String userId = request.queryParam("userId").orElse("user-001");

        ctx.logger().info("FraudStep: scoring fraud for userId={}", userId);

        // Read AuthContext — needed for user tier and authentication status.
        AuthContext auth = heap.get(HeapKeys.auth(userId))
            .orElseThrow(() -> new IllegalStateException(
                "FraudStep requires AuthContext for userId '" + userId + "'. "
                + "Ensure AuthStep runs before FraudStep."
            ));

        // Read PricingResult — needed for transaction amount.
        PricingResult pricing = heap.get(HeapKeys.PRICING_CURRENT)
            .orElseThrow(() -> new IllegalStateException(
                "FraudStep requires PricingResult in heap. "
                + "Ensure PricingStep runs before FraudStep."
            ));

        // Compute fraud score using data from BOTH heap entries.
        // In production, this would call an ML model or rules engine.
        //
        // Simple heuristic for this example:
        // - Premium users get a lower base risk score
        // - High-value transactions increase risk
        // - Authenticated users have lower risk
        double riskScore = 0.1; // base risk

        if (!"premium".equals(auth.tier())) {
            riskScore += 0.2; // non-premium users are higher risk
        }

        if (pricing.finalPrice() > 500.0) {
            riskScore += 0.3; // high-value transactions are higher risk
        }

        if (!auth.authenticated()) {
            riskScore += 0.4; // unauthenticated users are much higher risk
        }

        // Cap risk score at 1.0
        riskScore = Math.min(riskScore, 1.0);

        // Approve if risk is below threshold
        boolean approved = riskScore < 0.7;
        String reason = approved ? "low-risk-transaction" : "high-risk-flagged";

        FraudScore fraud = new FraudScore(
            riskScore,
            approved,
            reason,
            "heuristic-v1"     // model: identifies which scoring model was used
        );

        // Publish fraud score for the orchestrator (and potentially other steps) to read.
        heap.publish(HeapKeys.FRAUD_RESULT, fraud);

        ctx.logger().info("FraudStep: published FraudScore, riskScore={}, approved={}",
            riskScore, approved);

        return KubeFnResponse.ok(
            "{\"step\": \"fraud\", \"riskScore\": " + riskScore + ", \"approved\": " + approved + "}"
        );
    }
}
