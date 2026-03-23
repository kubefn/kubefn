package com.example.patterns;

/**
 * CONTRACT-FIRST: The shared type that Developer A and Developer B agree on.
 * ============================================================================
 *
 * In the contract-first workflow, the FIRST thing you define is the data shape.
 * Before any implementation exists, both teams agree: "This is what LoyaltyPoints
 * looks like. This is the heap key pattern. Go build."
 *
 * WHY a Java record?
 * - Records are immutable — once created, the data cannot be changed
 * - Records auto-generate equals(), hashCode(), toString()
 * - Records are compact — no boilerplate getters/setters
 * - Records enforce the contract at compile time — if you add a field,
 *   all producers and consumers get a compile error until updated
 *
 * WHERE DOES THIS LIVE IN A REAL PROJECT?
 * Once the contract is stable, it should be moved to the kubefn-contracts
 * module so that all function modules can depend on it. During initial
 * development, it can live in a shared library or even be duplicated
 * (the record definition is small enough that duplication is acceptable
 * as a temporary measure).
 *
 * HEAP KEY CONVENTION:
 * Each contract type defines a static heapKey() method that returns the
 * key pattern. This keeps the key definition close to the type definition,
 * making it easy to discover. HeapKeys in kubefn-contracts follows this
 * same convention for standard contract types.
 */
record LoyaltyPoints(
    String userId,      // The user this loyalty data belongs to
    int points,         // Current loyalty points balance
    String tier,        // Loyalty tier: "bronze", "silver", "gold", "platinum"
    double multiplier   // Points multiplier for the current tier (e.g., 1.5x for gold)
) {
    /**
     * Generates a heap key for a specific user's loyalty points.
     *
     * Convention: "loyalty:<userId>"
     *
     * WHY a method instead of a constant?
     * Because loyalty points are per-user (like AuthContext), not singleton
     * (like PRICING_CURRENT). Each user has their own heap entry.
     *
     * @param userId the user identifier
     * @return the heap key string
     */
    public static String heapKey(String userId) {
        return "loyalty:" + userId;
    }
}
