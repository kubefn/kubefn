package com.kubefn.runtime.heap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HeapExchange schema evolution: SchemaVersion, SchemaRegistry,
 * and HeapEnvelope working together to prevent breaking changes when
 * functions are hot-swapped.
 */
class SchemaEvolutionTest {

    private SchemaRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SchemaRegistry(SchemaRegistry.IncompatibilityPolicy.BLOCK);
    }

    // --- SchemaVersion compatibility ---

    @Test
    void sameMajorVersionIsCompatible() {
        var v1_0 = new SchemaVersion("pricing:result", 1, 0, "pricing-group", new String[]{"order-group"});
        var v1_2 = new SchemaVersion("pricing:result", 1, 2, "pricing-group", new String[]{"order-group"});

        assertTrue(v1_0.isCompatibleWith(v1_2));
        assertTrue(v1_2.isCompatibleWith(v1_0));
    }

    @Test
    void differentMajorVersionIsIncompatible() {
        var v1 = new SchemaVersion("pricing:result", 1, 0, "pricing-group", new String[]{"order-group"});
        var v2 = new SchemaVersion("pricing:result", 2, 0, "pricing-group", new String[]{"order-group"});

        assertFalse(v1.isCompatibleWith(v2));
        assertFalse(v2.isCompatibleWith(v1));
    }

    @Test
    void differentKeyIsIncompatible() {
        var a = new SchemaVersion("pricing:result", 1, 0, "pricing-group", new String[]{});
        var b = new SchemaVersion("order:summary", 1, 0, "order-group", new String[]{});

        assertFalse(a.isCompatibleWith(b));
    }

    @Test
    void schemaVersionRejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () ->
                new SchemaVersion(null, 1, 0, "group", new String[]{}));
        assertThrows(IllegalArgumentException.class, () ->
                new SchemaVersion("", 1, 0, "group", new String[]{}));
        assertThrows(IllegalArgumentException.class, () ->
                new SchemaVersion("key", -1, 0, "group", new String[]{}));
        assertThrows(IllegalArgumentException.class, () ->
                new SchemaVersion("key", 1, 0, null, new String[]{}));
    }

    @Test
    void versionStringFormat() {
        var v = new SchemaVersion("key", 2, 3, "group", new String[]{});
        assertEquals("2.3", v.version());
    }

    // --- SchemaRegistry: producer/consumer registration ---

    @Test
    void producerAndConsumerSameVersionIsCompatible() {
        var producerSchema = new SchemaVersion("pricing:result", 1, 0, "pricing", new String[]{"orders"});
        var consumerSchema = new SchemaVersion("pricing:result", 1, 0, "orders", new String[]{});

        registry.registerProducer("pricing", "PricingFn", "pricing:result", producerSchema);
        registry.registerConsumer("orders", "OrderFn", "pricing:result", consumerSchema);

        List<SchemaRegistry.CompatibilityIssue> issues = registry.checkCompatibility("pricing:result");
        assertTrue(issues.isEmpty(), "Same version should have no compatibility issues");
    }

    @Test
    void producerV2ConsumerV1IsIncompatible() {
        var producerSchema = new SchemaVersion("pricing:result", 2, 0, "pricing", new String[]{"orders"});
        var consumerSchema = new SchemaVersion("pricing:result", 1, 0, "orders", new String[]{});

        registry.registerProducer("pricing", "PricingFn", "pricing:result", producerSchema);
        registry.registerConsumer("orders", "OrderFn", "pricing:result", consumerSchema);

        List<SchemaRegistry.CompatibilityIssue> issues = registry.checkCompatibility("pricing:result");
        assertFalse(issues.isEmpty(), "Major version mismatch should produce compatibility issues");
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).message().contains("major version mismatch"));
    }

    @Test
    void producerV1_2ConsumerV1_1IsCompatible() {
        var producerSchema = new SchemaVersion("pricing:result", 1, 2, "pricing", new String[]{"orders"});
        var consumerSchema = new SchemaVersion("pricing:result", 1, 1, "orders", new String[]{});

        registry.registerProducer("pricing", "PricingFn", "pricing:result", producerSchema);
        registry.registerConsumer("orders", "OrderFn", "pricing:result", consumerSchema);

        List<SchemaRegistry.CompatibilityIssue> issues = registry.checkCompatibility("pricing:result");
        assertTrue(issues.isEmpty(), "Minor version upgrade should be compatible");
    }

    // --- SchemaRegistry: hot-swap validation ---

    @Test
    void validateHotSwapBlocksWhenBreakingConsumers() {
        // Consumer is already registered expecting v1
        var consumerSchema = new SchemaVersion("pricing:result", 1, 0, "orders", new String[]{});
        registry.registerConsumer("orders", "OrderFn", "pricing:result", consumerSchema);

        // Hot-swap pricing group with v2 — should block
        var newSchema = new SchemaVersion("pricing:result", 2, 0, "pricing", new String[]{"orders"});

        SchemaRegistry.SchemaIncompatibleException ex = assertThrows(
                SchemaRegistry.SchemaIncompatibleException.class,
                () -> registry.validateHotSwap("pricing", List.of(newSchema))
        );

        assertEquals("pricing", ex.getGroupName());
        assertFalse(ex.getIssues().isEmpty());
        assertTrue(ex.getMessage().contains("blocked"));
    }

    @Test
    void validateHotSwapAllowsCompatibleUpgrade() {
        var consumerSchema = new SchemaVersion("pricing:result", 1, 0, "orders", new String[]{});
        registry.registerConsumer("orders", "OrderFn", "pricing:result", consumerSchema);

        // Hot-swap pricing group with v1.3 (minor bump) — should pass
        var newSchema = new SchemaVersion("pricing:result", 1, 3, "pricing", new String[]{"orders"});

        List<SchemaRegistry.CompatibilityIssue> issues =
                registry.validateHotSwap("pricing", List.of(newSchema));

        assertTrue(issues.isEmpty(), "Minor version bump should not block hot-swap");
    }

    @Test
    void validateHotSwapWarnsInsteadOfBlockingWhenPolicyIsWarn() {
        var warnRegistry = new SchemaRegistry(SchemaRegistry.IncompatibilityPolicy.WARN);

        var consumerSchema = new SchemaVersion("pricing:result", 1, 0, "orders", new String[]{});
        warnRegistry.registerConsumer("orders", "OrderFn", "pricing:result", consumerSchema);

        var newSchema = new SchemaVersion("pricing:result", 2, 0, "pricing", new String[]{"orders"});

        // Should NOT throw — WARN policy returns issues without blocking
        List<SchemaRegistry.CompatibilityIssue> issues =
                warnRegistry.validateHotSwap("pricing", List.of(newSchema));

        assertFalse(issues.isEmpty(), "Should still report issues even in WARN mode");
    }

    // --- SchemaRegistry: multiple producers conflict detection ---

    @Test
    void multipleProducersSameKeyDetectsVersionConflict() {
        // Two producers at different major versions + a consumer at v1
        // The v2 producer should conflict with the v1 consumer
        var schemaA = new SchemaVersion("shared:config", 1, 0, "groupA", new String[]{});
        var schemaB = new SchemaVersion("shared:config", 2, 0, "groupB", new String[]{});
        var consumer = new SchemaVersion("shared:config", 1, 0, "groupC", new String[]{});

        registry.registerProducer("groupA", "FnA", "shared:config", schemaA);
        registry.registerProducer("groupB", "FnB", "shared:config", schemaB);
        registry.registerConsumer("groupC", "FnC", "shared:config", consumer);

        List<SchemaRegistry.CompatibilityIssue> issues = registry.checkCompatibility("shared:config");
        assertFalse(issues.isEmpty(), "Producer v2 should conflict with consumer v1");
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("mismatch")));
    }

    @Test
    void multipleProducersSameMajorVersionNoConflict() {
        var schemaA = new SchemaVersion("shared:config", 1, 0, "groupA", new String[]{});
        var schemaB = new SchemaVersion("shared:config", 1, 2, "groupB", new String[]{});

        registry.registerProducer("groupA", "FnA", "shared:config", schemaA);
        registry.registerProducer("groupB", "FnB", "shared:config", schemaB);

        List<SchemaRegistry.CompatibilityIssue> issues = registry.checkCompatibility("shared:config");
        assertTrue(issues.isEmpty(), "Same major version across producers should not conflict");
    }

    // --- SchemaRegistry: admin queries ---

    @Test
    void getKeySchemaReturnsActiveVersion() {
        var schema = new SchemaVersion("pricing:result", 3, 1, "pricing", new String[]{});
        registry.registerProducer("pricing", "PricingFn", "pricing:result", schema);

        SchemaVersion active = registry.getKeySchema("pricing:result");
        assertNotNull(active);
        assertEquals(3, active.majorVersion());
        assertEquals(1, active.minorVersion());
    }

    @Test
    void getKeySchemaReturnsNullForUnknownKey() {
        assertNull(registry.getKeySchema("nonexistent:key"));
    }

    @Test
    void allSchemasReturnsRegisteredSchemas() {
        var schema1 = new SchemaVersion("pricing:result", 1, 0, "pricing", new String[]{});
        var schema2 = new SchemaVersion("order:summary", 2, 1, "orders", new String[]{});

        registry.registerProducer("pricing", "PricingFn", "pricing:result", schema1);
        registry.registerProducer("orders", "OrderFn", "order:summary", schema2);

        Map<String, SchemaVersion> all = registry.allSchemas();
        assertEquals(2, all.size());
        assertEquals("1.0", all.get("pricing:result").version());
        assertEquals("2.1", all.get("order:summary").version());
    }

    @Test
    void schemaHistoryTracksRegistrations() {
        var schema = new SchemaVersion("key", 1, 0, "group", new String[]{});
        registry.registerProducer("group", "Fn", "key", schema);
        registry.registerConsumer("other", "OtherFn", "key", schema);

        List<SchemaRegistry.SchemaHistoryEntry> history = registry.schemaHistory();
        assertTrue(history.size() >= 2, "History should contain at least 2 entries");
    }

    @Test
    void reRegistrationReplacesPreviousEntry() {
        var v1 = new SchemaVersion("key", 1, 0, "group", new String[]{});
        var v1_1 = new SchemaVersion("key", 1, 1, "group", new String[]{});

        registry.registerProducer("group", "Fn", "key", v1);
        registry.registerProducer("group", "Fn", "key", v1_1);

        // Should only have one producer entry, not two
        List<SchemaRegistry.ProducerEntry> producers = registry.getProducers("key");
        assertEquals(1, producers.size());
        assertEquals("1.1", producers.get(0).version().version());
    }

    // --- HeapEnvelope ---

    @Test
    void envelopeWrapsValueWithSchemaMetadata() {
        var envelope = new HeapEnvelope<>(
                Map.of("price", 42.0),
                "pricing:result",
                1, 2,
                System.currentTimeMillis(),
                "rev-abc123",
                Map.of("format", "json")
        );

        assertEquals(42.0, ((Map<?, ?>) envelope.value()).get("price"));
        assertEquals("pricing:result", envelope.schemaKey());
        assertEquals(1, envelope.majorVersion());
        assertEquals(2, envelope.minorVersion());
        assertEquals("1.2", envelope.version());
        assertEquals("rev-abc123", envelope.producerRevision());
        assertEquals("json", envelope.metadata().get("format"));
    }

    @Test
    void envelopeCompatibilityCheck() {
        var envelope = new HeapEnvelope<>("data", "key", 2, 1,
                System.currentTimeMillis(), "rev-1", Map.of());

        assertTrue(envelope.isCompatibleWith(2));
        assertFalse(envelope.isCompatibleWith(1));
        assertFalse(envelope.isCompatibleWith(3));
    }

    @Test
    void envelopeRejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () ->
                new HeapEnvelope<>("val", null, 1, 0, 0L, "rev", Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new HeapEnvelope<>("val", "key", -1, 0, 0L, "rev", Map.of()));
    }

    @Test
    void envelopeDefaultsNullMetadataToEmptyMap() {
        var envelope = new HeapEnvelope<>("val", "key", 1, 0, 0L, "rev", null);
        assertNotNull(envelope.metadata());
        assertTrue(envelope.metadata().isEmpty());
    }

    // --- Policy switching ---

    @Test
    void policyCanBeSwitchedAtRuntime() {
        assertEquals(SchemaRegistry.IncompatibilityPolicy.BLOCK, registry.getPolicy());

        registry.setPolicy(SchemaRegistry.IncompatibilityPolicy.WARN);
        assertEquals(SchemaRegistry.IncompatibilityPolicy.WARN, registry.getPolicy());
    }
}
