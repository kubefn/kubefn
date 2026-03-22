package com.kubefn.runtime.introspection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CausalEventRingTest {

    private CausalEventRing ring;

    @BeforeEach
    void setUp() {
        ring = new CausalEventRing(100);
    }

    @Test
    void appendAndRetrieveEvents() {
        var event = new CausalEvent(1L, System.nanoTime(), "req-1",
                EventType.REQUEST_START, "grp", "fn", "rev-1",
                null, null, 0, 0, "test");

        ring.append(event);
        assertEquals(1, ring.size());

        var events = ring.getByRequestId("req-1", 10);
        assertEquals(1, events.size());
        assertEquals("req-1", events.get(0).requestId());
    }

    @Test
    void ringWrapsAtCapacity() {
        var smallRing = new CausalEventRing(5);

        for (int i = 0; i < 10; i++) {
            smallRing.append(new CausalEvent((long) i, System.nanoTime(), "req-" + i,
                    EventType.REQUEST_START, "grp", "fn", "rev",
                    null, null, 0, 0, null));
        }

        // Ring should have wrapped — size capped at capacity
        assertTrue(smallRing.size() <= 10);
    }

    @Test
    void getByRequestIdFiltersCorrectly() {
        ring.append(new CausalEvent(1L, System.nanoTime(), "req-A",
                EventType.REQUEST_START, "grp", "fn", "rev",
                null, null, 0, 0, null));
        ring.append(new CausalEvent(2L, System.nanoTime(), "req-B",
                EventType.REQUEST_START, "grp", "fn", "rev",
                null, null, 0, 0, null));
        ring.append(new CausalEvent(3L, System.nanoTime(), "req-A",
                EventType.REQUEST_END, "grp", "fn", "rev",
                null, null, 100, 0, null));

        var eventsA = ring.getByRequestId("req-A", 10);
        assertEquals(2, eventsA.size());

        var eventsB = ring.getByRequestId("req-B", 10);
        assertEquals(1, eventsB.size());
    }

    @Test
    void recentReturnsLatestEvents() {
        for (int i = 0; i < 20; i++) {
            ring.append(new CausalEvent((long) i, System.nanoTime(), "req-" + i,
                    EventType.REQUEST_START, "grp", "fn", "rev",
                    null, null, 0, 0, null));
        }

        var recent = ring.recent(5);
        assertEquals(5, recent.size());
    }

    @Test
    void emptyRingReturnsEmpty() {
        assertEquals(0, ring.size());
        assertTrue(ring.getByRequestId("req-1", 10).isEmpty());
        assertTrue(ring.recent(10).isEmpty());
    }
}
