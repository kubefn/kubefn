package com.kubefn.runtime.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe router that maps HTTP method+path to function entries.
 * Supports prefix matching: request to /greet/extra matches route /greet.
 * Routes can be added/removed at runtime for hot reload.
 */
public class FunctionRouter {

    private static final Logger log = LoggerFactory.getLogger(FunctionRouter.class);

    // Exact match: method+path → entry
    private final ConcurrentHashMap<RouteKey, FunctionEntry> exactRoutes = new ConcurrentHashMap<>();

    // All registered paths for prefix matching (sorted longest-first)
    private volatile List<String> sortedPaths = List.of();

    /**
     * Register a function for the given method(s) and path.
     */
    public void register(String[] methods, String path, FunctionEntry entry) {
        for (String method : methods) {
            RouteKey key = new RouteKey(method, path);
            exactRoutes.put(key, entry);
            log.info("Registered route: {} {} → {}.{} [group={}, rev={}]",
                    method, path, entry.groupName(), entry.functionName(),
                    entry.groupName(), entry.revisionId());
        }
        rebuildSortedPaths();
    }

    /**
     * Unregister all routes for a given group (used during reload).
     */
    public void unregisterGroup(String groupName) {
        exactRoutes.entrySet().removeIf(e -> e.getValue().groupName().equals(groupName));
        rebuildSortedPaths();
        log.info("Unregistered all routes for group: {}", groupName);
    }

    /**
     * Resolve a request method+path to a function entry.
     * Tries exact match first, then prefix match (longest prefix wins).
     *
     * @return the matched entry and the sub-path after the route prefix, or empty
     */
    public Optional<ResolvedRoute> resolve(String method, String requestPath) {
        // Normalize
        String normalizedPath = requestPath;
        if (normalizedPath.length() > 1 && normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }

        // Try exact match first
        RouteKey exactKey = new RouteKey(method, normalizedPath);
        FunctionEntry exact = exactRoutes.get(exactKey);
        if (exact != null) {
            return Optional.of(new ResolvedRoute(exact, ""));
        }

        // Prefix match: longest prefix wins
        for (String prefix : sortedPaths) {
            if (normalizedPath.startsWith(prefix)) {
                String subPath = normalizedPath.substring(prefix.length());
                if (subPath.isEmpty() || subPath.startsWith("/")) {
                    RouteKey prefixKey = new RouteKey(method, prefix);
                    FunctionEntry entry = exactRoutes.get(prefixKey);
                    if (entry != null) {
                        return Optional.of(new ResolvedRoute(entry, subPath));
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Get all registered routes for admin/introspection.
     */
    public Map<RouteKey, FunctionEntry> allRoutes() {
        return Collections.unmodifiableMap(exactRoutes);
    }

    public int routeCount() {
        return exactRoutes.size();
    }

    private void rebuildSortedPaths() {
        Set<String> paths = new HashSet<>();
        for (RouteKey key : exactRoutes.keySet()) {
            paths.add(key.path());
        }
        List<String> sorted = new ArrayList<>(paths);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        this.sortedPaths = List.copyOf(sorted);
    }

    /**
     * A resolved route with the matched entry and remaining sub-path.
     */
    public record ResolvedRoute(FunctionEntry entry, String subPath) {}
}
