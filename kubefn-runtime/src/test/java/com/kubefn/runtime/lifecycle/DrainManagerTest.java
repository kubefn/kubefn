package com.kubefn.runtime.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DrainManagerTest {

    private DrainManager manager;

    @BeforeEach
    void setUp() {
        manager = new DrainManager();
    }

    @Test
    void acquireAndReleaseTracksInFlight() {
        assertTrue(manager.acquireRequest("grp"));
        assertEquals(1, manager.inFlightCount("grp"));

        assertTrue(manager.acquireRequest("grp"));
        assertEquals(2, manager.inFlightCount("grp"));

        manager.releaseRequest("grp");
        assertEquals(1, manager.inFlightCount("grp"));

        manager.releaseRequest("grp");
        assertEquals(0, manager.inFlightCount("grp"));
    }

    @Test
    void drainWithNoInFlightReturnsImmediately() {
        boolean drained = manager.drainAndWait("grp", 1000);
        assertTrue(drained);
    }

    @Test
    @Timeout(5)
    void drainWaitsForInFlightToComplete() throws InterruptedException {
        manager.acquireRequest("grp");

        AtomicBoolean drained = new AtomicBoolean(false);
        CountDownLatch drainStarted = new CountDownLatch(1);

        Thread drainThread = Thread.startVirtualThread(() -> {
            drainStarted.countDown();
            drained.set(manager.drainAndWait("grp", 5000));
        });

        drainStarted.await();
        Thread.sleep(100); // Let drain start
        assertFalse(drained.get()); // Still waiting

        manager.releaseRequest("grp"); // Complete the in-flight request
        drainThread.join(2000);
        assertTrue(drained.get());
    }

    @Test
    void drainRejectsNewRequests() {
        manager.acquireRequest("grp");

        // Start drain in background
        Thread.startVirtualThread(() -> manager.drainAndWait("grp", 5000));

        // Give drain a moment to set draining flag
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        assertTrue(manager.isDraining("grp"));
        assertFalse(manager.acquireRequest("grp")); // Rejected during drain

        manager.releaseRequest("grp"); // Let drain complete
    }

    @Test
    @Timeout(3)
    void drainTimesOutIfRequestsStuck() {
        manager.acquireRequest("grp"); // Never released

        boolean drained = manager.drainAndWait("grp", 500); // 500ms timeout
        assertFalse(drained); // Should timeout
    }
}
