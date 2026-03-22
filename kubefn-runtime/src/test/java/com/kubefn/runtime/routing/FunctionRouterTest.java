package com.kubefn.runtime.routing;

import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FunctionRouterTest {

    private FunctionRouter router;
    private final KubeFnHandler dummyHandler = req -> KubeFnResponse.ok("ok");

    @BeforeEach
    void setUp() {
        router = new FunctionRouter();
    }

    @Test
    void registerAndResolveExactMatch() {
        var entry = new FunctionEntry("grp", "Fn", "com.Fn", "rev-1", dummyHandler);
        router.register(new String[]{"GET"}, "/hello", entry);

        var resolved = router.resolve("GET", "/hello");
        assertTrue(resolved.isPresent());
        assertEquals("Fn", resolved.get().entry().functionName());
        assertEquals("", resolved.get().subPath());
    }

    @Test
    void resolveReturnsEmptyForUnregisteredPath() {
        assertTrue(router.resolve("GET", "/unknown").isEmpty());
    }

    @Test
    void resolveReturnsEmptyForWrongMethod() {
        var entry = new FunctionEntry("grp", "Fn", "com.Fn", "rev-1", dummyHandler);
        router.register(new String[]{"POST"}, "/data", entry);

        assertTrue(router.resolve("GET", "/data").isEmpty());
    }

    @Test
    void prefixMatchWithSubPath() {
        var entry = new FunctionEntry("grp", "Fn", "com.Fn", "rev-1", dummyHandler);
        router.register(new String[]{"GET"}, "/api", entry);

        var resolved = router.resolve("GET", "/api/users/123");
        assertTrue(resolved.isPresent());
        assertEquals("/users/123", resolved.get().subPath());
    }

    @Test
    void longestPrefixWins() {
        var entry1 = new FunctionEntry("grp", "Short", "com.Short", "rev-1", dummyHandler);
        var entry2 = new FunctionEntry("grp", "Long", "com.Long", "rev-1", dummyHandler);
        router.register(new String[]{"GET"}, "/api", entry1);
        router.register(new String[]{"GET"}, "/api/users", entry2);

        var resolved = router.resolve("GET", "/api/users/123");
        assertTrue(resolved.isPresent());
        assertEquals("Long", resolved.get().entry().functionName());
        assertEquals("/123", resolved.get().subPath());
    }

    @Test
    void unregisterGroupRemovesAllRoutes() {
        var entry1 = new FunctionEntry("grpA", "Fn1", "com.Fn1", "rev-1", dummyHandler);
        var entry2 = new FunctionEntry("grpA", "Fn2", "com.Fn2", "rev-1", dummyHandler);
        var entry3 = new FunctionEntry("grpB", "Fn3", "com.Fn3", "rev-1", dummyHandler);
        router.register(new String[]{"GET"}, "/a", entry1);
        router.register(new String[]{"GET"}, "/b", entry2);
        router.register(new String[]{"GET"}, "/c", entry3);

        assertEquals(3, router.routeCount());

        router.unregisterGroup("grpA");
        assertEquals(1, router.routeCount());
        assertTrue(router.resolve("GET", "/a").isEmpty());
        assertTrue(router.resolve("GET", "/b").isEmpty());
        assertTrue(router.resolve("GET", "/c").isPresent());
    }

    @Test
    void multipleMethodsRegistered() {
        var entry = new FunctionEntry("grp", "Fn", "com.Fn", "rev-1", dummyHandler);
        router.register(new String[]{"GET", "POST"}, "/data", entry);

        assertEquals(2, router.routeCount());
        assertTrue(router.resolve("GET", "/data").isPresent());
        assertTrue(router.resolve("POST", "/data").isPresent());
        assertTrue(router.resolve("DELETE", "/data").isEmpty());
    }

    @Test
    void trailingSlashNormalized() {
        var entry = new FunctionEntry("grp", "Fn", "com.Fn", "rev-1", dummyHandler);
        router.register(new String[]{"GET"}, "/hello/", entry);

        // Both with and without trailing slash should match
        assertTrue(router.resolve("GET", "/hello").isPresent());
        assertTrue(router.resolve("GET", "/hello/").isPresent());
    }
}
