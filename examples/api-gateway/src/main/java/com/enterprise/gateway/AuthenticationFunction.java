package com.enterprise.gateway;

import io.kubefn.api.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Simulates JWT validation. Parses the Authorization header, validates the
 * token structure, checks expiry and scopes, and publishes an authenticated
 * identity to the heap for downstream functions.
 */
@FnRoute(path = "/gw/auth", methods = {"POST"})
@FnGroup("api-gateway")
public class AuthenticationFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Simulated token database: token -> claims
    private static final Map<String, Map<String, Object>> TOKEN_STORE = Map.of(
            "eyJhbGciOiJSUzI1NiJ9.admin", Map.of(
                    "sub", "admin-001",
                    "name", "Alice Admin",
                    "roles", List.of("admin", "user"),
                    "scopes", List.of("read", "write", "delete"),
                    "exp", Long.MAX_VALUE,
                    "iss", "kubefn-auth"
            ),
            "eyJhbGciOiJSUzI1NiJ9.user", Map.of(
                    "sub", "user-042",
                    "name", "Bob User",
                    "roles", List.of("user"),
                    "scopes", List.of("read"),
                    "exp", Long.MAX_VALUE,
                    "iss", "kubefn-auth"
            ),
            "eyJhbGciOiJSUzI1NiJ9.service", Map.of(
                    "sub", "svc-payment",
                    "name", "Payment Service",
                    "roles", List.of("service"),
                    "scopes", List.of("read", "write"),
                    "exp", Long.MAX_VALUE,
                    "iss", "kubefn-auth"
            ),
            "eyJhbGciOiJSUzI1NiJ9.expired", Map.of(
                    "sub", "user-099",
                    "name", "Expired User",
                    "roles", List.of("user"),
                    "scopes", List.of("read"),
                    "exp", 1000L, // Already expired
                    "iss", "kubefn-auth"
            )
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String authHeader = request.header("Authorization")
                .or(() -> request.queryParam("token"))
                .orElse("");

        // Strip "Bearer " prefix
        String token = authHeader.startsWith("Bearer ")
                ? authHeader.substring(7).trim()
                : authHeader.trim();

        if (token.isEmpty()) {
            Map<String, Object> denied = Map.of(
                    "authenticated", false,
                    "error", "missing_token",
                    "message", "Authorization header is required"
            );
            ctx.heap().publish("gw:auth-result", denied, Map.class);
            return KubeFnResponse.status(401).body(denied);
        }

        // Look up token
        Map<String, Object> claims = TOKEN_STORE.get(token);
        if (claims == null) {
            Map<String, Object> denied = Map.of(
                    "authenticated", false,
                    "error", "invalid_token",
                    "message", "Token signature verification failed"
            );
            ctx.heap().publish("gw:auth-result", denied, Map.class);
            return KubeFnResponse.status(401).body(denied);
        }

        // Check expiry
        long exp = ((Number) claims.get("exp")).longValue();
        if (System.currentTimeMillis() > exp) {
            Map<String, Object> denied = Map.of(
                    "authenticated", false,
                    "error", "token_expired",
                    "subject", claims.get("sub"),
                    "message", "Token has expired. Please re-authenticate."
            );
            ctx.heap().publish("gw:auth-result", denied, Map.class);
            return KubeFnResponse.status(401).body(denied);
        }

        // Check required scope for the request method
        List<String> scopes = (List<String>) claims.getOrDefault("scopes", List.of());
        String method = request.method();
        boolean scopeValid = switch (method) {
            case "GET", "HEAD", "OPTIONS" -> scopes.contains("read");
            case "POST", "PUT", "PATCH" -> scopes.contains("write");
            case "DELETE" -> scopes.contains("delete");
            default -> scopes.contains("read");
        };

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("authenticated", true);
        identity.put("scopeValid", scopeValid);
        identity.put("subject", claims.get("sub"));
        identity.put("name", claims.get("name"));
        identity.put("roles", claims.get("roles"));
        identity.put("scopes", scopes);
        identity.put("issuer", claims.get("iss"));

        ctx.heap().publish("gw:auth-result", identity, Map.class);

        if (!scopeValid) {
            identity.put("error", "insufficient_scope");
            identity.put("requiredScope", method.equals("DELETE") ? "delete" : "write");
            return KubeFnResponse.status(403).body(identity);
        }

        return KubeFnResponse.ok(identity);
    }
}
