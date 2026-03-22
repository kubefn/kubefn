package com.kubefn.api;

import java.util.HashMap;
import java.util.Map;

/**
 * Response from a function handler. Builder pattern for ergonomic construction.
 */
public final class KubeFnResponse {

    private int statusCode;
    private final Map<String, String> headers;
    private Object body;

    private KubeFnResponse(int statusCode) {
        this.statusCode = statusCode;
        this.headers = new HashMap<>();
    }

    /**
     * Create a 200 OK response with a body (serialized to JSON by the runtime).
     */
    public static KubeFnResponse ok(Object body) {
        return new KubeFnResponse(200).body(body);
    }

    /**
     * Create a response with a specific status code.
     */
    public static KubeFnResponse status(int code) {
        return new KubeFnResponse(code);
    }

    /**
     * Create a 204 No Content response.
     */
    public static KubeFnResponse noContent() {
        return new KubeFnResponse(204);
    }

    /**
     * Create a 400 Bad Request response.
     */
    public static KubeFnResponse badRequest(Object body) {
        return new KubeFnResponse(400).body(body);
    }

    /**
     * Create a 500 Internal Server Error response.
     */
    public static KubeFnResponse error(Object body) {
        return new KubeFnResponse(500).body(body);
    }

    public KubeFnResponse header(String name, String value) {
        this.headers.put(name, value);
        return this;
    }

    public KubeFnResponse body(Object body) {
        this.body = body;
        return this;
    }

    public int statusCode() { return statusCode; }
    public Map<String, String> headers() { return headers; }
    public Object body() { return body; }
}
