package com.enterprise.events;

import com.kubefn.api.*;

import java.util.*;

/**
 * Generates actionable alerts from detected anomalies. Applies alert routing
 * rules based on severity, device owner, and event type. Supports alert
 * deduplication and escalation thresholds.
 */
@FnRoute(path = "/events/alert", methods = {"POST"})
@FnGroup("event-processor")
public class AlertGeneratorFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Alert routing rules: severity -> notification channels
    private static final Map<String, List<String>> ALERT_CHANNELS = Map.of(
            "critical", List.of("pagerduty", "slack-critical", "email-oncall", "sms"),
            "warning", List.of("slack-warnings", "email-team"),
            "info", List.of("slack-monitoring")
    );

    // Escalation config: owner -> escalation contacts
    private static final Map<String, Map<String, String>> ESCALATION_CONTACTS = Map.of(
            "Logistics Division", Map.of(
                    "primary", "logistics-oncall@company.com",
                    "escalation", "vp-logistics@company.com",
                    "slackChannel", "#logistics-alerts"),
            "Manufacturing Division", Map.of(
                    "primary", "manufacturing-oncall@company.com",
                    "escalation", "plant-manager@company.com",
                    "slackChannel", "#factory-alerts"),
            "IT Operations", Map.of(
                    "primary", "it-oncall@company.com",
                    "escalation", "cto@company.com",
                    "slackChannel", "#it-ops-alerts")
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var anomalyResult = ctx.heap().get("event:anomaly-result", Map.class).orElse(Map.of());
        var enriched = ctx.heap().get("event:enriched", Map.class).orElse(Map.of());

        boolean anomalyDetected = Boolean.TRUE.equals(anomalyResult.get("anomalyDetected"));
        String severity = (String) anomalyResult.getOrDefault("severity", "none");
        String deviceId = (String) anomalyResult.getOrDefault("deviceId", "unknown");
        String eventType = (String) anomalyResult.getOrDefault("eventType", "unknown");
        List<Map<String, Object>> anomalies = (List<Map<String, Object>>)
                anomalyResult.getOrDefault("anomalies", List.of());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", anomalyResult.get("eventId"));
        result.put("deviceId", deviceId);

        if (!anomalyDetected) {
            result.put("alertGenerated", false);
            result.put("reason", "no_anomaly_detected");

            ctx.heap().publish("event:alert-result", result, Map.class);
            return KubeFnResponse.ok(result);
        }

        // Deduplication check: suppress if same device+type alerted recently
        String dedupeKey = "alert-dedup:" + deviceId + ":" + eventType + ":" + severity;
        var recentAlert = ctx.cache().get(dedupeKey, Map.class);
        if (recentAlert.isPresent()) {
            Map<String, Object> suppressed = (Map<String, Object>) recentAlert.get();
            int suppressedCount = ((Number) suppressed.getOrDefault("suppressedCount", 0)).intValue() + 1;
            suppressed = new LinkedHashMap<>(suppressed);
            suppressed.put("suppressedCount", suppressedCount);
            ctx.cache().put(dedupeKey, suppressed, java.time.Duration.ofMinutes(5));

            result.put("alertGenerated", false);
            result.put("reason", "deduplicated");
            result.put("suppressedCount", suppressedCount);
            result.put("originalAlertId", suppressed.get("alertId"));

            ctx.heap().publish("event:alert-result", result, Map.class);
            return KubeFnResponse.ok(result);
        }

        // Generate alert
        String alertId = "ALT-" + System.nanoTime() % 1000000;
        List<String> channels = ALERT_CHANNELS.getOrDefault(severity, List.of("slack-monitoring"));

        // Determine notification contacts from device owner
        Map<String, Object> device = (Map<String, Object>) enriched.getOrDefault("device", Map.of());
        String owner = (String) device.getOrDefault("owner", "Unknown");
        Map<String, String> contacts = ESCALATION_CONTACTS.getOrDefault(owner, Map.of());

        // Build alert summary
        StringBuilder summary = new StringBuilder();
        summary.append("[").append(severity.toUpperCase()).append("] ");
        summary.append("Device ").append(deviceId).append(" - ");
        for (Map<String, Object> anomaly : anomalies) {
            summary.append(anomaly.get("type")).append(": ").append(anomaly.get("message")).append("; ");
        }

        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("alertId", alertId);
        alert.put("severity", severity);
        alert.put("deviceId", deviceId);
        alert.put("eventType", eventType);
        alert.put("summary", summary.toString().trim());
        alert.put("anomalies", anomalies);
        alert.put("channels", channels);
        alert.put("contacts", contacts);
        alert.put("owner", owner);
        alert.put("requiresAcknowledgment", "critical".equals(severity));
        alert.put("autoEscalateAfterMinutes", "critical".equals(severity) ? 15 : 60);
        alert.put("generatedAt", System.currentTimeMillis());

        // Store dedup entry
        ctx.cache().put(dedupeKey, Map.of(
                "alertId", alertId,
                "suppressedCount", 0,
                "firstSeen", System.currentTimeMillis()
        ), java.time.Duration.ofMinutes(5));

        result.put("alertGenerated", true);
        result.put("alert", alert);

        ctx.heap().publish("event:alert-result", result, Map.class);
        return KubeFnResponse.ok(result);
    }
}
