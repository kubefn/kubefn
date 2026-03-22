package com.kubefn.runtime.heap;

import java.util.Map;

/**
 * Versioned wrapper that adds schema metadata to every heap object.
 *
 * <p>When a function publishes to the HeapExchange, the raw value is wrapped
 * in a HeapEnvelope that records schema version, publication time, and the
 * producer revision that created it. Consumers can inspect the envelope to
 * verify compatibility before unwrapping.
 *
 * <p>The metadata map is extensible — functions can attach arbitrary key-value
 * pairs (e.g., content-type hints, serialization format, TTL overrides)
 * without changing the envelope schema itself.
 */
public record HeapEnvelope<T>(
    T value,
    String schemaKey,
    int majorVersion,
    int minorVersion,
    long publishedAt,
    String producerRevision,
    Map<String, Object> metadata
) {
    public HeapEnvelope {
        if (schemaKey == null || schemaKey.isBlank()) {
            throw new IllegalArgumentException("Schema key must not be null or blank");
        }
        if (majorVersion < 0) {
            throw new IllegalArgumentException("Major version must be non-negative");
        }
        if (minorVersion < 0) {
            throw new IllegalArgumentException("Minor version must be non-negative");
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    /**
     * Check if this envelope's major version matches the consumer's expectation.
     * Minor version mismatches are tolerated — additive changes are backward-compatible.
     */
    public boolean isCompatibleWith(int expectedMajor) {
        return this.majorVersion == expectedMajor;
    }

    /**
     * Human-readable version string, e.g. "2.3".
     */
    public String version() {
        return majorVersion + "." + minorVersion;
    }

    @Override
    public String toString() {
        return "HeapEnvelope[key=" + schemaKey + ", v=" + version()
                + ", producer=" + producerRevision
                + ", published=" + publishedAt + "]";
    }
}
