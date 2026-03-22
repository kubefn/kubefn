package com.enterprise;

import com.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the notification recipient list with their contact details
 * and communication preferences. Handles user lookups, group expansion,
 * opt-out filtering, and quiet hours enforcement.
 */
@FnRoute(path = "/notify/recipients", methods = {"POST"})
@FnGroup("notification-engine")
public class RecipientResolverFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var template = ctx.heap().get("notify:template", Map.class).orElse(Map.of());
        String category = (String) template.getOrDefault("category", "informational");
        String priority = (String) template.getOrDefault("priority", "medium");

        String body = request.bodyAsString();
        String recipientId = extractField(body, "recipientId", "user-001");
        String recipientType = extractField(body, "recipientType", "individual");

        List<Map<String, Object>> recipients = new ArrayList<>();

        if ("group".equals(recipientType)) {
            // Expand group to individual recipients
            List<String> memberIds = expandGroup(recipientId);
            for (String memberId : memberIds) {
                Map<String, Object> recipient = resolveRecipient(memberId, category, priority);
                if (recipient != null) recipients.add(recipient);
            }
        } else {
            Map<String, Object> recipient = resolveRecipient(recipientId, category, priority);
            if (recipient != null) recipients.add(recipient);
        }

        // Filter out opted-out recipients
        long totalResolved = recipients.size();
        recipients.removeIf(r -> Boolean.TRUE.equals(r.get("optedOut")));
        long afterOptOut = recipients.size();

        // Filter quiet hours (non-critical only)
        if (!"critical".equals(priority)) {
            recipients.forEach(r -> {
                if (Boolean.TRUE.equals(r.get("inQuietHours"))) {
                    r.put("deferUntil", computeQuietHoursEnd((String) r.get("timezone")));
                    r.put("deferred", true);
                }
            });
        }

        long deferredCount = recipients.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("deferred"))).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recipientType", recipientType);
        result.put("recipients", recipients);
        result.put("stats", Map.of(
                "totalResolved", totalResolved,
                "afterOptOutFilter", afterOptOut,
                "activeRecipients", recipients.size(),
                "deferredRecipients", deferredCount,
                "optedOutFiltered", totalResolved - afterOptOut
        ));
        result.put("resolvedAt", System.currentTimeMillis());

        ctx.heap().publish("notify:recipients", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private Map<String, Object> resolveRecipient(String userId, String category, String priority) {
        long hash = Math.abs(userId.hashCode());

        // Simulate user lookup
        String firstName = "User" + (hash % 1000);
        String lastName = "Smith";
        String email = userId + "@example.com";
        String phone = "+1" + (2000000000L + hash % 8000000000L);
        String timezone = resolveTimezone(hash);

        // Communication preferences (deterministic per user)
        boolean emailEnabled = true; // Nearly everyone has email
        boolean smsEnabled = (hash % 3) != 0; // ~67% have SMS
        boolean pushEnabled = (hash % 4) != 0; // ~75% have push
        boolean slackEnabled = (hash % 5) == 0; // ~20% have Slack

        // Category-specific opt-outs
        boolean optedOut = false;
        if ("billing".equals(category) && (hash % 10) == 0) optedOut = true;
        if ("onboarding".equals(category) && (hash % 8) == 0) optedOut = true;

        // Quiet hours check
        int currentHour = java.time.LocalTime.now().getHour();
        boolean inQuietHours = currentHour >= 22 || currentHour < 7;

        // Frequency cap: limit non-critical notifications
        int recentNotifications = (int) (hash % 15); // Simulated count
        boolean frequencyCapped = !"critical".equals(priority) && recentNotifications > 10;

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("userId", userId);
        recipient.put("firstName", firstName);
        recipient.put("lastName", lastName);
        recipient.put("email", email);
        recipient.put("phone", smsEnabled ? phone : null);
        recipient.put("timezone", timezone);
        recipient.put("preferences", Map.of(
                "emailEnabled", emailEnabled,
                "smsEnabled", smsEnabled,
                "pushEnabled", pushEnabled,
                "slackEnabled", slackEnabled
        ));
        recipient.put("optedOut", optedOut);
        recipient.put("inQuietHours", inQuietHours);
        recipient.put("frequencyCapped", frequencyCapped);
        recipient.put("recentNotificationCount", recentNotifications);
        recipient.put("deferred", false);

        return recipient;
    }

    private List<String> expandGroup(String groupId) {
        // Simulate group expansion
        long hash = Math.abs(groupId.hashCode());
        int size = 3 + (int) (hash % 8);
        List<String> members = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            members.add("user-" + ((hash + i * 31) % 10000));
        }
        return members;
    }

    private String resolveTimezone(long hash) {
        String[] timezones = {"America/New_York", "America/Chicago", "America/Denver",
                "America/Los_Angeles", "Europe/London", "Europe/Berlin", "Asia/Tokyo", "Asia/Singapore"};
        return timezones[(int) (hash % timezones.length)];
    }

    private long computeQuietHoursEnd(String timezone) {
        // Return timestamp for 7 AM in recipient's timezone (simplified)
        return System.currentTimeMillis() + (7 * 3600 * 1000L);
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
