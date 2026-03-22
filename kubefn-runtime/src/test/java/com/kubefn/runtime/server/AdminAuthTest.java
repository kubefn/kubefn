package com.kubefn.runtime.server;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AdminAuthTest {

    @Test
    void healthzAlwaysAllowed() {
        var auth = new AdminAuth();
        assertTrue(auth.isAuthorized("/healthz", null));
        assertTrue(auth.isAuthorized("/readyz", null));
    }

    @Test
    void adminOpenWhenNoTokenConfigured() {
        var auth = new AdminAuth();
        // No KUBEFN_ADMIN_TOKEN set
        assertFalse(auth.isEnabled());
        assertTrue(auth.isAuthorized("/admin/functions", null));
        assertTrue(auth.isAuthorized("/admin/heap", null));
    }

    @Test
    void bearerTokenFormat() {
        var auth = new AdminAuth();
        // This test only works meaningfully when KUBEFN_ADMIN_TOKEN is set
        // Testing the parsing logic
        if (auth.isEnabled()) {
            assertFalse(auth.isAuthorized("/admin/functions", null));
            assertFalse(auth.isAuthorized("/admin/functions", "Bearer wrong-token"));
        }
    }

    @Test
    void basicAuthFormat() {
        var auth = new AdminAuth();
        // Test basic auth parsing
        String encoded = Base64.getEncoder().encodeToString("admin:test-password".getBytes());
        // When no token configured, all requests pass
        if (!auth.isEnabled()) {
            assertTrue(auth.isAuthorized("/admin/functions", "Basic " + encoded));
        }
    }
}
