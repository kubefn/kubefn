package com.example.patterns;

import com.kubefn.api.FnContextAware;
import com.kubefn.api.FnContext;
import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnRequest;
import com.kubefn.api.KubeFnResponse;
import com.kubefn.api.FnRoute;
import com.kubefn.api.FnGroup;
import com.kubefn.api.HeapExchange;


/**
 * DEVELOPER B's CONSUMER — codes against LoyaltyPoints BEFORE A deploys.
 * ========================================================================
 *
 * THIS IS THE KEY INSIGHT OF THE CONTRACT-FIRST PATTERN:
 * Developer B writes this consumer on Day 1, using .orElse() to provide
 * a default LoyaltyPoints. The function works immediately — it compiles,
 * runs, and returns valid responses. When Developer A deploys the producer
 * on Day 3, this code picks up real data with ZERO modifications.
 *
 * THE .orElse() SERVES THREE PURPOSES:
 * 1. Development stub — B can build and test without A's producer
 * 2. Graceful degradation — if A's producer fails in production, B still works
 * 3. Documentation — the default values show what "no data" looks like
 *
 * COMPARE TO MICROSERVICES:
 * In a microservice architecture, B would need:
 * - A mock server mimicking A's REST API
 * - Error handling for HTTP 404/500/timeout
 * - JSON deserialization with null checks
 * - Feature flags to switch between mock and real
 *
 * With KubeFn contract-first:
 * - One .orElse() call handles everything
 * - The contract type IS the documentation
 * - No feature flags — the Optional handles presence/absence naturally
 *
 * WHAT THIS FUNCTION DOES:
 * Shows a user's loyalty rewards page, including their points, tier,
 * and available rewards. Works with or without the LoyaltyProducer.
 */
@FnRoute(path = "/loyalty/rewards", methods = {"GET"})
@FnGroup("patterns")
public class LoyaltyConsumer implements KubeFnHandler, FnContextAware {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        HeapExchange heap = ctx.heap();

        String userId = request.queryParam("userId").orElse("user-001");

        ctx.logger().info("LoyaltyConsumer: loading rewards for userId={}", userId);

        // ---------------------------------------------------------------
        // THE CONTRACT-FIRST READ
        // ---------------------------------------------------------------
        // Read LoyaltyPoints using the SAME key pattern defined on the contract.
        // If the producer has not yet deployed (or has failed), .orElse()
        // provides a default that lets this function return a valid response.
        //
        // DEFAULT VALUES EXPLAINED:
        // - points: 0      — new user with no history
        // - tier: "bronze"  — the lowest tier, shown as "Welcome! Start earning!"
        // - multiplier: 1.0 — base rate, no tier bonus
        //
        // These defaults are chosen to be SAFE and HONEST:
        // - We don't show inflated points that don't exist
        // - We don't promise a tier the user hasn't earned
        // - The UI can check points==0 to show an onboarding flow
        LoyaltyPoints loyalty = heap.get(LoyaltyPoints.heapKey(userId), LoyaltyPoints.class)
            .orElse(new LoyaltyPoints(userId, 0, "bronze", 1.0));

        // Determine if we're using real data or the fallback.
        // This is useful for logging and for the response metadata.
        boolean isRealData = loyalty.points() > 0;

        if (!isRealData) {
            // Log at INFO, not WARN — during development, this is EXPECTED.
            // Once the producer deploys, these logs will stop appearing.
            ctx.logger().info("LoyaltyConsumer: using default LoyaltyPoints for userId={}. "
                + "This is normal if the loyalty producer has not deployed yet.", userId);
        }

        // ---------------------------------------------------------------
        // BUILD THE REWARDS RESPONSE
        // ---------------------------------------------------------------
        // Calculate available rewards based on the loyalty data.
        // This logic works identically whether the data is real or defaulted.
        // That is the beauty of contract-first: the consumer code path is
        // the same regardless of data source.
        int redeemablePoints = loyalty.points() - (loyalty.points() % 100); // round down to nearest 100
        double redeemableValue = redeemablePoints * 0.01; // 1 cent per point
        int pointsToNextTier = calculatePointsToNextTier(loyalty.tier(), loyalty.points());

        ctx.logger().info("LoyaltyConsumer: userId={}, tier={}, points={}, realData={}",
            userId, loyalty.tier(), loyalty.points(), isRealData);

        return KubeFnResponse.ok(
            "{\"rewards\": {"
            + "  \"userId\": \"" + loyalty.userId() + "\","
            + "  \"currentTier\": \"" + loyalty.tier() + "\","
            + "  \"points\": " + loyalty.points() + ","
            + "  \"multiplier\": " + loyalty.multiplier() + ","
            + "  \"redeemablePoints\": " + redeemablePoints + ","
            + "  \"redeemableValue\": " + redeemableValue + ","
            + "  \"pointsToNextTier\": " + pointsToNextTier + ","
            + "  \"nextTier\": \"" + nextTier(loyalty.tier()) + "\""
            + "},"
            + "\"_meta\": {"
            + "  \"dataSource\": \"" + (isRealData ? "heap" : "default") + "\","
            + "  \"producerDeployed\": " + isRealData + ","
            + "  \"heapKey\": \"" + LoyaltyPoints.heapKey(userId) + "\""
            + "}}"
        );
    }

    /**
     * Calculate how many more points the user needs to reach the next tier.
     * Returns 0 if the user is already at the highest tier.
     */
    private int calculatePointsToNextTier(String currentTier, int currentPoints) {
        return switch (currentTier) {
            case "bronze"   -> 1000 - currentPoints;
            case "silver"   -> 2500 - currentPoints;
            case "gold"     -> 5000 - currentPoints;
            case "platinum" -> 0; // already at the top
            default         -> 1000;
        };
    }

    /**
     * Returns the next tier name, or "platinum" if already at the top.
     */
    private String nextTier(String currentTier) {
        return switch (currentTier) {
            case "bronze"   -> "silver";
            case "silver"   -> "gold";
            case "gold"     -> "platinum";
            case "platinum" -> "platinum";
            default         -> "silver";
        };
    }
}
