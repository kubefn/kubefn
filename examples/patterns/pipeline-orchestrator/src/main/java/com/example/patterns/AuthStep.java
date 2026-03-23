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
import com.kubefn.contracts.HeapKeys;

import java.util.List;


/**
 * PIPELINE STEP 1: Authentication
 * ================================
 * Validates the user and publishes an AuthContext to HeapExchange.
 *
 * This is a SIBLING function — it is called by the CheckoutOrchestrator
 * via ctx.getFunction(AuthStep.class).handle(request). It does NOT have
 * its own HTTP route because it is an internal pipeline step, not a
 * public endpoint.
 *
 * WHY publish to heap instead of returning data:
 * The orchestrator calls multiple steps sequentially. Each step publishes
 * its result to heap. The orchestrator then reads ALL results from heap
 * in one place. This decouples steps from each other — AuthStep does not
 * need to know about PricingStep, and vice versa.
 */
@FnRoute(path = "/checkout/auth", methods = {"POST"})
@FnGroup("patterns")
public class AuthStep implements KubeFnHandler, FnContextAware {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        HeapExchange heap = ctx.heap();

        // Extract userId from the request.
        // In a real system, this would come from a JWT token or session cookie.
        String userId = request.queryParam("userId").orElse("user-001");

        ctx.logger().info("AuthStep: authenticating userId={}", userId);

        // Create an AuthContext with the user's identity and permissions.
        // In production, this would involve token validation, database lookups, etc.
        AuthContext auth = new AuthContext(
            userId,             // userId: the authenticated user's ID
            true,               // authenticated: true after successful validation
            "premium",          // tier: the user's subscription tier
            List.of("buyer", "reviewer"),   // roles: what the user can do
            List.of("checkout", "review"),  // permissions: specific action grants
            System.currentTimeMillis() + 3600000,  // tokenExpiry: 1 hour from now
            "session-" + System.currentTimeMillis() // sessionId: unique session
        );

        // Publish to heap using a DYNAMIC key: "auth:<userId>".
        // Dynamic keys allow multiple AuthContexts to coexist in the heap
        // (one per user), which is needed for multi-tenant pipelines.
        heap.publish(HeapKeys.auth(userId), auth);

        ctx.logger().info("AuthStep: published AuthContext for userId={}, tier={}",
            userId, auth.tier());

        return KubeFnResponse.ok("{\"step\": \"auth\", \"userId\": \"" + userId + "\", \"status\": \"authenticated\"}");
    }
}
