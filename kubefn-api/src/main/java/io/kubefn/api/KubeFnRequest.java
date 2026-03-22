package io.kubefn.api;

import java.util.Map;
import java.util.Optional;

/**
 * Immutable representation of an incoming HTTP request.
 * Wraps method, path, headers, query params, and body.
 */
public record KubeFnRequest(
        String method,
        String path,
        String subPath,
        Map<String, String> headers,
        Map<String, String> queryParams,
        byte[] body
) {

    /**
     * Get a query parameter by name.
     */
    public Optional<String> queryParam(String name) {
        return Optional.ofNullable(queryParams.get(name));
    }

    /**
     * Get a header value by name (case-insensitive lookup should be done by caller).
     */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name));
    }

    /**
     * Get the body as a UTF-8 string.
     */
    public String bodyAsString() {
        return body != null ? new String(body, java.nio.charset.StandardCharsets.UTF_8) : "";
    }
}
