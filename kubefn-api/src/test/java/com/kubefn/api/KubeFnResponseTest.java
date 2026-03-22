package com.kubefn.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KubeFnResponseTest {

    @Test
    void okResponseHasStatus200() {
        var response = KubeFnResponse.ok(Map.of("key", "value"));
        assertEquals(200, response.statusCode());
        assertNotNull(response.body());
    }

    @Test
    void errorResponseHasStatus500() {
        var response = KubeFnResponse.error("something failed");
        assertEquals(500, response.statusCode());
        assertEquals("something failed", response.body());
    }

    @Test
    void statusResponseUsesGivenCode() {
        var response = KubeFnResponse.status(429).body("rate limited");
        assertEquals(429, response.statusCode());
    }

    @Test
    void headersAreAddedCorrectly() {
        var response = KubeFnResponse.ok("test")
                .header("X-Custom", "value1")
                .header("X-Another", "value2");
        assertEquals("value1", response.headers().get("X-Custom"));
        assertEquals("value2", response.headers().get("X-Another"));
        assertEquals(2, response.headers().size());
    }

    @Test
    void noContentResponseHasStatus204() {
        var response = KubeFnResponse.noContent();
        assertEquals(204, response.statusCode());
        assertNull(response.body());
    }

    @Test
    void badRequestResponseHasStatus400() {
        var response = KubeFnResponse.badRequest("invalid input");
        assertEquals(400, response.statusCode());
    }
}
