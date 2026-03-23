package com.kubefn.testing;

import com.kubefn.api.KubeFnRequest;

import java.util.Map;

/**
 * Factory for creating test KubeFnRequest instances.
 *
 * <pre>{@code
 * // Empty GET request (most common)
 * var response = fn.handle(TestRequest.empty());
 *
 * // POST with body
 * var response = fn.handle(TestRequest.withBody("{\"sku\":\"PROD-42\"}"));
 *
 * // GET with query params
 * var response = fn.handle(TestRequest.get("userId", "user-1"));
 * }</pre>
 */
public final class TestRequest {

    private TestRequest() {}

    /** An empty GET request. */
    public static KubeFnRequest empty() {
        return new KubeFnRequest("GET", "/test", "", Map.of(), Map.of(), new byte[0]);
    }

    /** A POST request with a JSON body. */
    public static KubeFnRequest withBody(String body) {
        return new KubeFnRequest("POST", "/test", "",
                Map.of("Content-Type", "application/json"),
                Map.of(),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A GET request with one query parameter. */
    public static KubeFnRequest get(String paramKey, String paramValue) {
        return new KubeFnRequest("GET", "/test", "",
                Map.of(),
                Map.of(paramKey, paramValue),
                new byte[0]);
    }

    /** A GET request with multiple query parameters. */
    public static KubeFnRequest get(Map<String, String> params) {
        return new KubeFnRequest("GET", "/test", "",
                Map.of(),
                params,
                new byte[0]);
    }

    /** A POST request with body and headers. */
    public static KubeFnRequest post(String body, Map<String, String> headers) {
        return new KubeFnRequest("POST", "/test", "",
                headers,
                Map.of(),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
