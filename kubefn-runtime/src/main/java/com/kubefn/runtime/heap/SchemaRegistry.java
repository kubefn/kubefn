package com.kubefn.runtime.heap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Schema evolution registry for the HeapExchange.
 *
 * <p>Tracks which functions produce and consume which heap keys at which schema
 * versions. When a function is loaded or hot-swapped, the registry validates
 * that new schema versions won't break existing consumers.
 *
 * <p>This is the gatekeeper that prevents the classic shared-memory hazard:
 * Function A publishes v2 of an object while Function B still expects v1.
 *
 * <p>Thread-safe via ConcurrentHashMap. Designed for concurrent function
 * loading during startup and hot-swap during live traffic.
 */
public class SchemaRegistry {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistry.class);

    /**
     * Policy for handling compatibility violations during hot-swap.
     */
    public enum IncompatibilityPolicy {
        /** Log a warning but allow the hot-swap to proceed. */
        WARN,
        /** Block the hot-swap entirely if any consumer would break. */
        BLOCK
    }

    // Key -> producer registrations (key = heap key pattern)
    private final ConcurrentHashMap<String, List<ProducerEntry>> producers = new ConcurrentHashMap<>();

    // Key -> consumer registrations (key = heap key pattern)
    private final ConcurrentHashMap<String, List<ConsumerEntry>> consumers = new ConcurrentHashMap<>();

    // Schema version history for debugging
    private final ConcurrentLinkedDeque<SchemaHistoryEntry> history = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY = 5_000;

    // Current active schema version per key
    private final ConcurrentHashMap<String, SchemaVersion> activeSchemas = new ConcurrentHashMap<>();

    private volatile IncompatibilityPolicy policy;

    public SchemaRegistry() {
        this(IncompatibilityPolicy.BLOCK);
    }

    public SchemaRegistry(IncompatibilityPolicy policy) {
        this.policy = policy;
    }

    /**
     * Register a function as a producer for a heap key at a specific schema version.
     * Called when a function loads into the runtime.
     *
     * @param group        the function group name
     * @param function     the function name
     * @param key          the heap key pattern this function publishes to
     * @param version      the schema version of the published objects
     */
    public void registerProducer(String group, String function, String key, SchemaVersion version) {
        var entry = new ProducerEntry(group, function, version, Instant.now());

        producers.compute(key, (k, existing) -> {
            List<ProducerEntry> list = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
            // Remove previous registration for same group+function (re-registration on hot-swap)
            list.removeIf(e -> e.group().equals(group) && e.function().equals(function));
            list.add(entry);
            return list;
        });

        activeSchemas.put(key, version);
        appendHistory(SchemaHistoryAction.PRODUCER_REGISTERED, key, group, function, version);

        log.info("SchemaRegistry: producer registered {}.{} for key '{}' at v{}",
                group, function, key, version.version());

        // Check if this producer version is compatible with all consumers
        List<CompatibilityIssue> issues = checkCompatibility(key);
        for (CompatibilityIssue issue : issues) {
            log.warn("SchemaRegistry: compatibility issue for key '{}': {}", key, issue.message());
        }
    }

    /**
     * Register a function as a consumer for a heap key at a specific expected schema version.
     * Called when a function loads into the runtime.
     *
     * @param group        the function group name
     * @param function     the function name
     * @param key          the heap key pattern this function reads from
     * @param version      the schema version this consumer expects
     */
    public void registerConsumer(String group, String function, String key, SchemaVersion version) {
        var entry = new ConsumerEntry(group, function, version, Instant.now());

        consumers.compute(key, (k, existing) -> {
            List<ConsumerEntry> list = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
            list.removeIf(e -> e.group().equals(group) && e.function().equals(function));
            list.add(entry);
            return list;
        });

        appendHistory(SchemaHistoryAction.CONSUMER_REGISTERED, key, group, function, version);

        log.info("SchemaRegistry: consumer registered {}.{} for key '{}' expecting v{}",
                group, function, key, version.version());

        // Check if the current producer version is compatible with this consumer
        List<CompatibilityIssue> issues = checkCompatibility(key);
        for (CompatibilityIssue issue : issues) {
            log.warn("SchemaRegistry: compatibility issue for key '{}': {}", key, issue.message());
        }
    }

    /**
     * Check compatibility between all producers and consumers for a given heap key.
     *
     * @param key the heap key to check
     * @return list of compatibility issues (empty = all compatible)
     */
    public List<CompatibilityIssue> checkCompatibility(String key) {
        List<ProducerEntry> keyProducers = producers.getOrDefault(key, List.of());
        List<ConsumerEntry> keyConsumers = consumers.getOrDefault(key, List.of());

        if (keyProducers.isEmpty() || keyConsumers.isEmpty()) {
            return List.of();
        }

        List<CompatibilityIssue> issues = new ArrayList<>();

        // Check each producer against each consumer
        for (ProducerEntry producer : keyProducers) {
            for (ConsumerEntry consumer : keyConsumers) {
                if (!producer.version().isCompatibleWith(consumer.version())) {
                    String message = String.format(
                            "Producer %s.%s publishes key '%s' at v%s but consumer %s.%s expects v%s "
                                    + "(major version mismatch: %d vs %d)",
                            producer.group(), producer.function(), key, producer.version().version(),
                            consumer.group(), consumer.function(), consumer.version().version(),
                            producer.version().majorVersion(), consumer.version().majorVersion()
                    );
                    issues.add(new CompatibilityIssue(
                            key, producer.group(), producer.function(), producer.version(),
                            consumer.group(), consumer.function(), consumer.version(),
                            message
                    ));
                }
            }
        }

        // Check for multiple producers on the same key (potential conflict)
        if (keyProducers.size() > 1) {
            // Check if all producers agree on the major version
            int firstMajor = keyProducers.get(0).version().majorVersion();
            for (int i = 1; i < keyProducers.size(); i++) {
                ProducerEntry other = keyProducers.get(i);
                if (other.version().majorVersion() != firstMajor) {
                    String message = String.format(
                            "Multiple producers for key '%s' disagree on major version: "
                                    + "%s.%s at v%s vs %s.%s at v%s",
                            key,
                            keyProducers.get(0).group(), keyProducers.get(0).function(),
                            keyProducers.get(0).version().version(),
                            other.group(), other.function(), other.version().version()
                    );
                    issues.add(new CompatibilityIssue(
                            key,
                            keyProducers.get(0).group(), keyProducers.get(0).function(),
                            keyProducers.get(0).version(),
                            other.group(), other.function(), other.version(),
                            message
                    ));
                }
            }
        }

        return Collections.unmodifiableList(issues);
    }

    /**
     * Validate whether a hot-swap would break existing consumers.
     *
     * <p>Called before deploying new function versions. Checks each new schema version
     * against all currently registered consumers. Returns the list of issues found.
     *
     * <p>If the policy is {@link IncompatibilityPolicy#BLOCK}, throws
     * {@link SchemaIncompatibleException} when issues are found.
     *
     * @param groupName         the group being hot-swapped
     * @param newSchemaVersions the new schema versions the group will publish
     * @return list of compatibility issues (empty if safe to proceed)
     * @throws SchemaIncompatibleException if policy is BLOCK and issues are found
     */
    public List<CompatibilityIssue> validateHotSwap(String groupName,
                                                     List<SchemaVersion> newSchemaVersions) {
        List<CompatibilityIssue> allIssues = new ArrayList<>();

        for (SchemaVersion newVersion : newSchemaVersions) {
            String key = newVersion.key();
            List<ConsumerEntry> keyConsumers = consumers.getOrDefault(key, List.of());

            for (ConsumerEntry consumer : keyConsumers) {
                if (!newVersion.isCompatibleWith(consumer.version())) {
                    String message = String.format(
                            "Hot-swap of group '%s' would publish key '%s' at v%s "
                                    + "but consumer %s.%s expects v%s (major version mismatch)",
                            groupName, key, newVersion.version(),
                            consumer.group(), consumer.function(), consumer.version().version()
                    );
                    allIssues.add(new CompatibilityIssue(
                            key, groupName, "(pending)", newVersion,
                            consumer.group(), consumer.function(), consumer.version(),
                            message
                    ));
                }
            }

            // Also check for producer conflicts with other groups
            List<ProducerEntry> keyProducers = producers.getOrDefault(key, List.of());
            for (ProducerEntry existingProducer : keyProducers) {
                // Skip producers in the same group (they'll be replaced)
                if (existingProducer.group().equals(groupName)) {
                    continue;
                }
                if (existingProducer.version().majorVersion() != newVersion.majorVersion()) {
                    String message = String.format(
                            "Hot-swap of group '%s' would publish key '%s' at v%s "
                                    + "but existing producer %s.%s publishes at v%s (version conflict)",
                            groupName, key, newVersion.version(),
                            existingProducer.group(), existingProducer.function(),
                            existingProducer.version().version()
                    );
                    allIssues.add(new CompatibilityIssue(
                            key, groupName, "(pending)", newVersion,
                            existingProducer.group(), existingProducer.function(),
                            existingProducer.version(), message
                    ));
                }
            }
        }

        if (!allIssues.isEmpty()) {
            for (CompatibilityIssue issue : allIssues) {
                log.warn("SchemaRegistry: hot-swap validation issue: {}", issue.message());
            }

            appendHistory(SchemaHistoryAction.HOTSWAP_VALIDATED, "(multiple)", groupName,
                    "(pending)", null);

            if (policy == IncompatibilityPolicy.BLOCK) {
                throw new SchemaIncompatibleException(groupName, allIssues);
            }
        }

        return Collections.unmodifiableList(allIssues);
    }

    /**
     * Get the current active schema version for a heap key.
     *
     * @param key the heap key
     * @return the active schema version, or null if no producer is registered
     */
    public SchemaVersion getKeySchema(String key) {
        return activeSchemas.get(key);
    }

    /**
     * Return all registered schemas for the admin API.
     *
     * @return unmodifiable map of heap key to active schema version
     */
    public Map<String, SchemaVersion> allSchemas() {
        return Collections.unmodifiableMap(activeSchemas);
    }

    /**
     * Return all registered producers for a key.
     */
    public List<ProducerEntry> getProducers(String key) {
        return List.copyOf(producers.getOrDefault(key, List.of()));
    }

    /**
     * Return all registered consumers for a key.
     */
    public List<ConsumerEntry> getConsumers(String key) {
        return List.copyOf(consumers.getOrDefault(key, List.of()));
    }

    /**
     * Return the schema history for debugging.
     */
    public List<SchemaHistoryEntry> schemaHistory() {
        return List.copyOf(history);
    }

    /**
     * Update the incompatibility policy at runtime.
     */
    public void setPolicy(IncompatibilityPolicy policy) {
        this.policy = policy;
        log.info("SchemaRegistry: incompatibility policy set to {}", policy);
    }

    /**
     * Get the current incompatibility policy.
     */
    public IncompatibilityPolicy getPolicy() {
        return policy;
    }

    // --- Internal helpers ---

    private void appendHistory(SchemaHistoryAction action, String key, String group,
                               String function, SchemaVersion version) {
        history.addFirst(new SchemaHistoryEntry(
                action, key, group, function,
                version != null ? version.version() : "n/a",
                Instant.now()
        ));
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
    }

    // --- Inner types ---

    public record ProducerEntry(
            String group,
            String function,
            SchemaVersion version,
            Instant registeredAt
    ) {}

    public record ConsumerEntry(
            String group,
            String function,
            SchemaVersion version,
            Instant registeredAt
    ) {}

    public record CompatibilityIssue(
            String key,
            String producerGroup,
            String producerFunction,
            SchemaVersion producerVersion,
            String consumerGroup,
            String consumerFunction,
            SchemaVersion consumerVersion,
            String message
    ) {}

    public enum SchemaHistoryAction {
        PRODUCER_REGISTERED,
        CONSUMER_REGISTERED,
        HOTSWAP_VALIDATED
    }

    public record SchemaHistoryEntry(
            SchemaHistoryAction action,
            String key,
            String group,
            String function,
            String version,
            Instant timestamp
    ) {}

    /**
     * Thrown when a hot-swap would break consumers and the policy is BLOCK.
     */
    public static class SchemaIncompatibleException extends RuntimeException {
        private final String groupName;
        private final List<CompatibilityIssue> issues;

        public SchemaIncompatibleException(String groupName, List<CompatibilityIssue> issues) {
            super("Hot-swap of group '" + groupName + "' blocked: "
                    + issues.size() + " schema incompatibility issue(s). "
                    + "First: " + issues.get(0).message());
            this.groupName = groupName;
            this.issues = List.copyOf(issues);
        }

        public String getGroupName() { return groupName; }
        public List<CompatibilityIssue> getIssues() { return issues; }
    }
}
