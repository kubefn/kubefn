package com.kubefn.runtime.heap;

/**
 * Schema version descriptor for heap objects.
 *
 * <p>Functions declare which version of a shared object they produce or consume.
 * Major version changes are breaking (different structure). Minor version changes
 * are additive (new fields with defaults) and backward-compatible.
 *
 * <p>Compatibility rule: same key + same major version = compatible. A consumer
 * expecting v1.x can read any v1.y object regardless of minor version.
 */
public record SchemaVersion(
    String key,              // heap key pattern (e.g., "pricing:*", "order:summary")
    int majorVersion,        // breaking changes increment this
    int minorVersion,        // additive changes increment this (backward compatible)
    String producerGroup,    // which group produces this
    String[] consumerGroups  // which groups consume this
) {
    public SchemaVersion {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Schema key must not be null or blank");
        }
        if (majorVersion < 0) {
            throw new IllegalArgumentException("Major version must be non-negative");
        }
        if (minorVersion < 0) {
            throw new IllegalArgumentException("Minor version must be non-negative");
        }
        if (producerGroup == null || producerGroup.isBlank()) {
            throw new IllegalArgumentException("Producer group must not be null or blank");
        }
        if (consumerGroups == null) {
            consumerGroups = new String[0];
        }
    }

    /**
     * Human-readable version string, e.g. "2.3".
     */
    public String version() {
        return majorVersion + "." + minorVersion;
    }

    /**
     * Two schema versions are compatible if they share the same key and major version.
     * Minor version differences are tolerated — they represent additive, non-breaking changes.
     */
    public boolean isCompatibleWith(SchemaVersion other) {
        return this.key.equals(other.key) && this.majorVersion == other.majorVersion;
    }

    @Override
    public String toString() {
        return "SchemaVersion[key=" + key + ", v=" + version()
                + ", producer=" + producerGroup + "]";
    }
}
