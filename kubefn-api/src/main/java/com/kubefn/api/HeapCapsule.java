package com.kubefn.api;

import java.time.Instant;

/**
 * An immutable capsule wrapping a shared heap object with metadata.
 * Provides version tracking, publisher identity, and lifecycle info.
 *
 * @param <T> the type of the wrapped object
 */
public record HeapCapsule<T>(
        String key,
        T value,
        Class<T> type,
        long version,
        String publisherGroup,
        String publisherFunction,
        Instant publishedAt
) {

    /**
     * Unwrap the capsule and get the direct heap reference.
     * This IS the same object in memory — zero copy.
     */
    public T unwrap() {
        return value;
    }
}
