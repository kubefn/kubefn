package com.enterprise;

import io.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Determines optimal delivery channels for each recipient based on
 * notification priority, category, user preferences, and channel-specific
 * constraints (rate limits, delivery windows).
 */
@FnRoute(path = "/notify/route", methods = {"POST"})
@FnGroup("notification-engine")
public class ChannelRouterFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Channel capacity constraints (messages per minute)
    private static final Map<String, Integer> CHANNEL_RATE_LIMITS = Map.of(
            "email", 10000,
            "sms", 500,
            "push", 50000,
            "slack", 2000
    );

    // Channel cost per message (USD, for prioritization)
    private static final Map<String, Double> CHANNEL_COSTS = Map.of(
            "email", 0.001,
            "sms", 0.05,
            "push", 0.0001,
            "slack", 0.0
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var template = ctx.heap().get("notify:template", Map.class).orElse(Map.of());
        var recipientData = ctx.heap().get("notify:recipients", Map.class).orElse(Map.of());

        String priority = (String) template.getOrDefault("priority", "medium");
        String category = (String) template.getOrDefault("category", "informational");
        List<Map<String, Object>> recipients = (List<Map<String, Object>>)
                recipientData.getOrDefault("recipients", List.of());

        List<Map<String, Object>> routingPlan = new ArrayList<>();
        int totalEmailRouted = 0, totalSmsRouted = 0, totalPushRouted = 0, totalSlackRouted = 0;
        double estimatedCost = 0.0;

        for (var recipient : recipients) {
            if (Boolean.TRUE.equals(recipient.get("frequencyCapped"))) {
                routingPlan.add(Map.of(
                        "userId", recipient.get("userId"),
                        "channels", List.of(),
                        "skipped", true,
                        "reason", "frequency_capped"
                ));
                continue;
            }

            Map<String, Object> prefs = (Map<String, Object>)
                    recipient.getOrDefault("preferences", Map.of());
            boolean deferred = Boolean.TRUE.equals(recipient.get("deferred"));

            List<Map<String, Object>> channels = new ArrayList<>();

            // Email: always the fallback channel
            if (Boolean.TRUE.equals(prefs.get("emailEnabled"))) {
                channels.add(channelConfig("email", determinePriority("email", priority),
                        deferred ? "deferred" : "immediate", estimateDeliveryTime("email")));
                totalEmailRouted++;
                estimatedCost += CHANNEL_COSTS.get("email");
            }

            // SMS: for critical/action-required only, or if user prefers
            boolean smsEligible = Boolean.TRUE.equals(prefs.get("smsEnabled"))
                    && ("critical".equals(priority) || "action_required".equals(category));
            if (smsEligible) {
                channels.add(channelConfig("sms", determinePriority("sms", priority),
                        deferred ? "deferred" : "immediate", estimateDeliveryTime("sms")));
                totalSmsRouted++;
                estimatedCost += CHANNEL_COSTS.get("sms");
            }

            // Push: for all non-deferred notifications
            if (Boolean.TRUE.equals(prefs.get("pushEnabled")) && !deferred) {
                channels.add(channelConfig("push", determinePriority("push", priority),
                        "immediate", estimateDeliveryTime("push")));
                totalPushRouted++;
                estimatedCost += CHANNEL_COSTS.get("push");
            }

            // Slack: for informational and security categories
            if (Boolean.TRUE.equals(prefs.get("slackEnabled"))
                    && ("security".equals(category) || "informational".equals(category))) {
                channels.add(channelConfig("slack", determinePriority("slack", priority),
                        "immediate", estimateDeliveryTime("slack")));
                totalSlackRouted++;
                estimatedCost += CHANNEL_COSTS.get("slack");
            }

            // Ensure at least one channel for critical notifications
            if (channels.isEmpty() && "critical".equals(priority)) {
                // Force email as safety net
                channels.add(channelConfig("email", "critical",
                        "immediate", estimateDeliveryTime("email")));
                totalEmailRouted++;
                estimatedCost += CHANNEL_COSTS.get("email");
            }

            Map<String, Object> routing = new LinkedHashMap<>();
            routing.put("userId", recipient.get("userId"));
            routing.put("channels", channels);
            routing.put("channelCount", channels.size());
            routing.put("deferred", deferred);
            routing.put("skipped", false);
            routingPlan.add(routing);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routingPlan", routingPlan);
        result.put("channelSummary", Map.of(
                "email", totalEmailRouted,
                "sms", totalSmsRouted,
                "push", totalPushRouted,
                "slack", totalSlackRouted,
                "totalMessages", totalEmailRouted + totalSmsRouted + totalPushRouted + totalSlackRouted
        ));
        result.put("estimatedCost", Map.of(
                "totalUsd", Math.round(estimatedCost * 10000.0) / 10000.0,
                "costBreakdown", Map.of(
                        "email", Math.round(totalEmailRouted * CHANNEL_COSTS.get("email") * 10000.0) / 10000.0,
                        "sms", Math.round(totalSmsRouted * CHANNEL_COSTS.get("sms") * 10000.0) / 10000.0,
                        "push", Math.round(totalPushRouted * CHANNEL_COSTS.get("push") * 10000.0) / 10000.0,
                        "slack", 0.0
                )
        ));
        result.put("recipientsRouted", routingPlan.size());

        ctx.heap().publish("notify:routing", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private Map<String, Object> channelConfig(String channel, String priority,
                                               String deliveryMode, int estimatedMs) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("channel", channel);
        config.put("priority", priority);
        config.put("deliveryMode", deliveryMode);
        config.put("estimatedDeliveryMs", estimatedMs);
        config.put("rateLimitPerMin", CHANNEL_RATE_LIMITS.get(channel));
        return config;
    }

    private String determinePriority(String channel, String notifPriority) {
        if ("critical".equals(notifPriority)) return "critical";
        if ("sms".equals(channel)) return "high"; // SMS is always high priority when used
        return notifPriority;
    }

    private int estimateDeliveryTime(String channel) {
        return switch (channel) {
            case "push" -> 50;
            case "slack" -> 200;
            case "email" -> 2000;
            case "sms" -> 3000;
            default -> 5000;
        };
    }
}
