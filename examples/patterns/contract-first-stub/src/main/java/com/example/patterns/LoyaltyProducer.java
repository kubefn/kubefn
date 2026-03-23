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
 * DEVELOPER A's PRODUCER — implements the LoyaltyPoints contract.
 * ================================================================
 *
 * TIMELINE:
 *   Day 1: A and B agree on the LoyaltyPoints record and heapKey() pattern.
 *   Day 1: B starts coding LoyaltyConsumer with .orElse() fallbacks.
 *   Day 3: A finishes this LoyaltyProducer and deploys it.
 *   Day 3: B's consumer automatically picks up real data — zero changes needed.
 *
 * WHY THIS PATTERN EXISTS:
 * In traditional microservices, B would need to:
 * 1. Wait for A to deploy a REST endpoint
 * 2. Write mock HTTP responses for testing
 * 3. Handle HTTP errors, timeouts, retries
 * 4. Parse JSON responses
 *
 * With KubeFn contract-first:
 * 1. B codes against the record type with .orElse() — works immediately
 * 2. No mocks needed — the fallback IS the mock
 * 3. No HTTP — heap access is a direct object reference
 * 4. No parsing — the object is already typed
 *
 * WHAT THIS FUNCTION DOES:
 * Calculates loyalty points for a user based on their purchase history
 * and publishes a LoyaltyPoints record to HeapExchange.
 */
@FnRoute(path = "/loyalty/calculate", methods = {"POST"})
@FnGroup("patterns")
public class LoyaltyProducer implements KubeFnHandler, FnContextAware {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        HeapExchange heap = ctx.heap();

        String userId = request.queryParam("userId").orElse("user-001");

        ctx.logger().info("LoyaltyProducer: calculating loyalty for userId={}", userId);

        // In production, this would query a database for purchase history,
        // calculate points earned, determine tier based on total spend, etc.
        // For this reference example, we use simulated values.
        int points = 2750;
        String tier = determineTier(points);
        double multiplier = determineMultiplier(tier);

        // Create the contract type instance.
        // This is the EXACT same type that LoyaltyConsumer reads.
        // Compile-time type safety ensures A and B agree on the shape.
        LoyaltyPoints loyalty = new LoyaltyPoints(
            userId,
            points,
            tier,
            multiplier
        );

        // Publish using the key pattern defined IN the contract type itself.
        // LoyaltyPoints.heapKey(userId) returns "loyalty:<userId>".
        //
        // WHY the key is defined on the contract type:
        // It keeps the key and the type co-located. Both producer and consumer
        // import LoyaltyPoints, so both have access to heapKey() — no chance
        // of a key mismatch.
        heap.publish(LoyaltyPoints.heapKey(userId), loyalty, LoyaltyPoints.class);

        ctx.logger().info("LoyaltyProducer: published LoyaltyPoints for userId={}, tier={}, points={}",
            userId, tier, points);

        return KubeFnResponse.ok(
            "{\"status\": \"loyalty_calculated\","
            + "\"userId\": \"" + userId + "\","
            + "\"points\": " + points + ","
            + "\"tier\": \"" + tier + "\","
            + "\"multiplier\": " + multiplier + "}"
        );
    }

    /**
     * Determine loyalty tier based on points.
     * In production, this logic would likely live in a shared service or
     * configuration, not hardcoded. Shown inline for teaching clarity.
     */
    private String determineTier(int points) {
        if (points >= 5000) return "platinum";
        if (points >= 2500) return "gold";
        if (points >= 1000) return "silver";
        return "bronze";
    }

    /**
     * Determine points multiplier based on tier.
     * Higher tiers earn points faster, incentivizing continued engagement.
     */
    private double determineMultiplier(String tier) {
        return switch (tier) {
            case "platinum" -> 2.0;
            case "gold"     -> 1.5;
            case "silver"   -> 1.2;
            default         -> 1.0;
        };
    }
}
