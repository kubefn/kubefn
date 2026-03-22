package com.enterprise;

import com.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hydrates the user context for AI inference: loads conversation history,
 * user preferences, session state, and permission scopes. This context
 * enriches downstream embedding and retrieval steps.
 */
@FnRoute(path = "/ai/hydrate", methods = {"POST"})
@FnGroup("ai-pipeline")
public class ContextHydrationFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String body = request.bodyAsString();
        String userId = extractField(body, "userId", "user-001");
        String query = extractField(body, "query", "");
        String sessionId = extractField(body, "sessionId", "sess-" + System.nanoTime());
        String namespace = extractField(body, "namespace", "default");

        // Load user profile (simulated from user store)
        Map<String, Object> userProfile = buildUserProfile(userId);

        // Load conversation history (simulated — last N turns)
        List<Map<String, String>> conversationHistory = buildConversationHistory(userId, sessionId);

        // Determine permission scopes for retrieval filtering
        List<String> permissionScopes = resolvePermissions(userId);

        // Build system prompt context based on user preferences
        String systemContext = buildSystemContext(userProfile, namespace);

        // Detect query intent for routing
        String queryIntent = classifyIntent(query);

        Map<String, Object> hydrated = new LinkedHashMap<>();
        hydrated.put("userId", userId);
        hydrated.put("sessionId", sessionId);
        hydrated.put("namespace", namespace);
        hydrated.put("query", query);
        hydrated.put("queryIntent", queryIntent);
        hydrated.put("userProfile", userProfile);
        hydrated.put("conversationHistory", conversationHistory);
        hydrated.put("permissionScopes", permissionScopes);
        hydrated.put("systemContext", systemContext);
        hydrated.put("maxTokens", determineMaxTokens(queryIntent));
        hydrated.put("temperature", determineTemperature(queryIntent));
        hydrated.put("hydratedAt", System.currentTimeMillis());

        ctx.heap().publish("ai:context", hydrated, Map.class);

        return KubeFnResponse.ok(hydrated);
    }

    private Map<String, Object> buildUserProfile(String userId) {
        long hash = Math.abs(userId.hashCode());
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("userId", userId);
        profile.put("role", hash % 3 == 0 ? "admin" : hash % 3 == 1 ? "engineer" : "analyst");
        profile.put("department", hash % 4 == 0 ? "engineering" : hash % 4 == 1 ? "product" : hash % 4 == 2 ? "data" : "operations");
        profile.put("preferredLanguage", "en");
        profile.put("expertiseLevel", hash % 2 == 0 ? "advanced" : "intermediate");
        return profile;
    }

    private List<Map<String, String>> buildConversationHistory(String userId, String sessionId) {
        List<Map<String, String>> history = new ArrayList<>();
        long hash = Math.abs((userId + sessionId).hashCode());
        if (hash % 3 == 0) {
            history.add(Map.of("role", "user", "content", "How do I configure auto-scaling?"));
            history.add(Map.of("role", "assistant", "content", "You can configure auto-scaling by setting replica policies in your deployment spec."));
        } else if (hash % 3 == 1) {
            history.add(Map.of("role", "user", "content", "What are the best practices for caching?"));
            history.add(Map.of("role", "assistant", "content", "Key caching best practices include TTL-based expiration, cache-aside pattern, and write-through for consistency."));
        }
        return history;
    }

    private List<String> resolvePermissions(String userId) {
        long hash = Math.abs(userId.hashCode());
        List<String> scopes = new ArrayList<>(List.of("public", "internal"));
        if (hash % 3 == 0) {
            scopes.add("confidential");
            scopes.add("admin");
        } else if (hash % 3 == 1) {
            scopes.add("confidential");
        }
        return scopes;
    }

    private String buildSystemContext(Map<String, Object> profile, String namespace) {
        String role = (String) profile.getOrDefault("role", "user");
        String expertise = (String) profile.getOrDefault("expertiseLevel", "intermediate");
        return "You are a helpful assistant in the '" + namespace + "' knowledge space. "
                + "The user is a " + expertise + "-level " + role + ". "
                + "Tailor responses to their expertise level.";
    }

    private String classifyIntent(String query) {
        String lower = query.toLowerCase();
        if (lower.contains("how") || lower.contains("tutorial") || lower.contains("guide")) return "instructional";
        if (lower.contains("error") || lower.contains("fix") || lower.contains("debug")) return "troubleshooting";
        if (lower.contains("compare") || lower.contains("difference") || lower.contains("vs")) return "comparative";
        if (lower.contains("what is") || lower.contains("define") || lower.contains("explain")) return "definitional";
        if (lower.contains("code") || lower.contains("example") || lower.contains("snippet")) return "code_generation";
        return "general";
    }

    private int determineMaxTokens(String intent) {
        return switch (intent) {
            case "code_generation" -> 2048;
            case "instructional" -> 1536;
            case "troubleshooting" -> 1024;
            case "comparative" -> 1024;
            default -> 512;
        };
    }

    private double determineTemperature(String intent) {
        return switch (intent) {
            case "code_generation" -> 0.1;
            case "definitional" -> 0.2;
            case "troubleshooting" -> 0.3;
            default -> 0.5;
        };
    }

    private String extractField(String body, String key, String defaultValue) {
        if (body == null || body.isEmpty()) return defaultValue;
        String search = "\"" + key + "\"";
        int idx = body.indexOf(search);
        if (idx < 0) return defaultValue;
        int colonIdx = body.indexOf(':', idx + search.length());
        if (colonIdx < 0) return defaultValue;
        int start = colonIdx + 1;
        while (start < body.length() && (body.charAt(start) == ' ' || body.charAt(start) == '"')) start++;
        int end = start;
        while (end < body.length() && body.charAt(end) != '"' && body.charAt(end) != ','
                && body.charAt(end) != '}') end++;
        return body.substring(start, end).trim();
    }
}
