package com.kubefn.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeapCapsuleTest {

    @Test
    void unwrapReturnsSameObject() {
        Map<String, String> data = Map.of("key", "value");
        var capsule = new HeapCapsule<>("test", data, Map.class, 1L,
                "group", "function", Instant.now());

        // Zero-copy: unwrap returns the SAME object reference
        assertSame(data, capsule.unwrap());
        assertSame(data, capsule.value());
    }

    @Test
    void capsulePreservesMetadata() {
        Instant now = Instant.now();
        var capsule = new HeapCapsule<>("mykey", "hello", String.class, 42L,
                "my-group", "MyFunction", now);

        assertEquals("mykey", capsule.key());
        assertEquals(String.class, capsule.type());
        assertEquals(42L, capsule.version());
        assertEquals("my-group", capsule.publisherGroup());
        assertEquals("MyFunction", capsule.publisherFunction());
        assertEquals(now, capsule.publishedAt());
    }
}
