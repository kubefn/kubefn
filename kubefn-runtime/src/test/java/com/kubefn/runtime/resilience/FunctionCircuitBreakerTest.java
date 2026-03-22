package com.kubefn.runtime.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FunctionCircuitBreakerTest {

    private FunctionCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new FunctionCircuitBreaker();
    }

    @Test
    void newBreakerPermitsCalls() {
        assertTrue(breaker.isCallPermitted("grp", "fn"));
    }

    @Test
    void successfulCallsKeepBreakerClosed() {
        for (int i = 0; i < 10; i++) {
            breaker.isCallPermitted("grp", "fn");
            breaker.recordSuccess("grp", "fn", 1_000_000);
        }
        assertTrue(breaker.isCallPermitted("grp", "fn"));

        var status = breaker.allStatus();
        assertNotNull(status.get("grp.fn"));
        assertEquals("CLOSED", status.get("grp.fn").state());
    }

    @Test
    void repeatedFailuresOpenBreaker() {
        // Need minimum 5 calls, 50% failure rate
        for (int i = 0; i < 10; i++) {
            breaker.isCallPermitted("grp", "fn");
            breaker.recordFailure("grp", "fn", 1_000_000,
                    new RuntimeException("test failure"));
        }

        // After 10 consecutive failures, breaker should be open
        var status = breaker.allStatus().get("grp.fn");
        assertEquals("OPEN", status.state());
    }

    @Test
    void separateBreakersPerFunction() {
        // Fail fn1
        for (int i = 0; i < 10; i++) {
            breaker.isCallPermitted("grp", "fn1");
            breaker.recordFailure("grp", "fn1", 1_000_000, new RuntimeException("fail"));
        }

        // fn2 should still be open
        assertTrue(breaker.isCallPermitted("grp", "fn2"));

        var statuses = breaker.allStatus();
        assertEquals("OPEN", statuses.get("grp.fn1").state());
        assertEquals("CLOSED", statuses.get("grp.fn2").state());
    }
}
