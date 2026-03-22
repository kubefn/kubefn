package com.kubefn.runtime.routing;

/**
 * A route key combining HTTP method and path for function dispatch.
 */
public record RouteKey(String method, String path) {

    public RouteKey {
        method = method.toUpperCase();
        // Normalize: strip trailing slash, ensure leading slash
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
    }
}
