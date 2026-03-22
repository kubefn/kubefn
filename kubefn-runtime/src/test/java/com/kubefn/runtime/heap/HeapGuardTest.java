package com.kubefn.runtime.heap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeapGuardTest {

    @Test
    void allowsPublishUnderLimit() {
        var guard = new HeapGuard(100, 1024 * 1024, 3600_000);
        assertNull(guard.checkPublish("key", "value", 0));
        assertNull(guard.checkPublish("key", "value", 99));
    }

    @Test
    void blocksPublishAtMaxCount() {
        var guard = new HeapGuard(5, 1024 * 1024, 3600_000);
        String error = guard.checkPublish("key", "value", 5);
        assertNotNull(error);
        assertTrue(error.contains("max capacity"));
    }

    @Test
    void tracksAccessForStaleness() throws InterruptedException {
        var guard = new HeapGuard(100, 1024 * 1024, 100); // 100ms stale threshold
        guard.recordPublish("key1", "value");
        guard.recordPublish("key2", "value");

        Thread.sleep(200); // Let them go stale

        var stale = guard.findStaleKeys();
        assertTrue(stale.contains("key1"));
        assertTrue(stale.contains("key2"));

        // Access key1 to refresh it
        guard.recordAccess("key1");
        stale = guard.findStaleKeys();
        assertFalse(stale.contains("key1"));
        assertTrue(stale.contains("key2"));
    }

    @Test
    void removeDecreasesEstimatedSize() {
        var guard = new HeapGuard(100, 1024 * 1024, 3600_000);
        guard.recordPublish("key1", Map.of("a", "b", "c", "d"));

        var before = guard.metrics();
        assertTrue(before.estimatedSizeBytes() > 0);

        guard.recordRemove("key1");
        var after = guard.metrics();
        assertEquals(0, after.estimatedSizeBytes());
    }

    @Test
    void metricsReportCorrectCounts() {
        var guard = new HeapGuard(100, 1024 * 1024, 3600_000);
        guard.recordPublish("k1", "v1");
        guard.recordPublish("k2", "v2");
        guard.recordPublish("k3", "v3");

        var metrics = guard.metrics();
        assertEquals(3, metrics.objectCount());
        assertEquals(100, metrics.maxObjects());
    }
}
