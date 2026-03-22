package com.enterprise;

import io.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full notification pipeline. Calls all upstream functions,
 * assembles the dispatch plan, simulates delivery to each channel, and
 * produces a comprehensive delivery report with timing metadata.
 */
@FnRoute(path = "/notify/send", methods = {"POST"})
@FnGroup("notification-engine")
public class NotificationDispatcherFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long startNanos = System.nanoTime();
        String notificationId = "NOTIF-" + System.currentTimeMillis();

        // Step 1: Resolve template
        ctx.getFunction(TemplateResolverFunction.class).handle(request);
        long afterTemplate = System.nanoTime();

        // Step 2: Resolve recipients
        ctx.getFunction(RecipientResolverFunction.class).handle(request);
        long afterRecipients = System.nanoTime();

        // Step 3: Route to channels
        ctx.getFunction(ChannelRouterFunction.class).handle(request);
        long afterRouting = System.nanoTime();

        // Step 4: Personalize content
        ctx.getFunction(ContentPersonalizerFunction.class).handle(request);
        long afterPersonalize = System.nanoTime();

        // Read all pipeline results
        var template = ctx.heap().get("notify:template", Map.class).orElse(Map.of());
        var recipientData = ctx.heap().get("notify:recipients", Map.class).orElse(Map.of());
        var routing = ctx.heap().get("notify:routing", Map.class).orElse(Map.of());
        var personalized = ctx.heap().get("notify:personalized", Map.class).orElse(Map.of());

        // Step 5: Simulate dispatch to each channel
        List<Map<String, Object>> personalizedContent = (List<Map<String, Object>>)
                personalized.getOrDefault("personalizedContent", List.of());

        List<Map<String, Object>> deliveryResults = new ArrayList<>();
        int successCount = 0, failedCount = 0, deferredCount = 0;
        Map<String, Integer> channelSuccessMap = new LinkedHashMap<>();
        channelSuccessMap.put("email", 0);
        channelSuccessMap.put("sms", 0);
        channelSuccessMap.put("push", 0);
        channelSuccessMap.put("slack", 0);

        for (var content : personalizedContent) {
            String userId = (String) content.get("userId");
            Map<String, Object> channelContent = (Map<String, Object>)
                    content.getOrDefault("channelContent", Map.of());

            List<Map<String, Object>> channelResults = new ArrayList<>();

            for (var entry : channelContent.entrySet()) {
                String channel = entry.getKey();
                Map<String, Object> payload = (Map<String, Object>) entry.getValue();

                // Simulate delivery (deterministic success/failure)
                long hash = Math.abs((userId + channel + notificationId).hashCode());
                boolean deliverySuccess = (hash % 20) != 0; // 95% success rate
                String messageId = channel.toUpperCase() + "-" + System.nanoTime();

                Map<String, Object> channelResult = new LinkedHashMap<>();
                channelResult.put("channel", channel);
                channelResult.put("messageId", messageId);
                channelResult.put("status", deliverySuccess ? "DELIVERED" : "FAILED");
                channelResult.put("deliveredAt", deliverySuccess ? System.currentTimeMillis() : null);

                if (!deliverySuccess) {
                    channelResult.put("errorCode", "CHANNEL_TIMEOUT");
                    channelResult.put("errorMessage", "Delivery to " + channel + " timed out after 5000ms");
                    channelResult.put("retryable", true);
                    channelResult.put("retryCount", 0);
                    channelResult.put("maxRetries", 3);
                } else {
                    channelSuccessMap.merge(channel, 1, Integer::sum);
                }

                channelResults.add(channelResult);
            }

            boolean allSuccess = channelResults.stream()
                    .allMatch(r -> "DELIVERED".equals(r.get("status")));
            boolean anySuccess = channelResults.stream()
                    .anyMatch(r -> "DELIVERED".equals(r.get("status")));

            if (allSuccess) successCount++;
            else if (!anySuccess) failedCount++;

            Map<String, Object> delivery = new LinkedHashMap<>();
            delivery.put("userId", userId);
            delivery.put("overallStatus", allSuccess ? "DELIVERED" : anySuccess ? "PARTIAL" : "FAILED");
            delivery.put("channelResults", channelResults);
            deliveryResults.add(delivery);
        }

        // Check for deferred recipients
        List<Map<String, Object>> routingPlan = (List<Map<String, Object>>)
                routing.getOrDefault("routingPlan", List.of());
        deferredCount = (int) routingPlan.stream()
                .filter(r -> {
                    List<?> channels = (List<?>) r.getOrDefault("channels", List.of());
                    return channels.stream().anyMatch(c ->
                            "deferred".equals(((Map<String, Object>) c).get("deliveryMode")));
                }).count();

        long endNanos = System.nanoTime();
        double totalMs = (endNanos - startNanos) / 1_000_000.0;

        // Build comprehensive dispatch report
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("notificationId", notificationId);
        report.put("eventType", template.get("eventType"));
        report.put("status", failedCount == 0 ? "COMPLETED" : "COMPLETED_WITH_FAILURES");
        report.put("deliveryResults", deliveryResults);
        report.put("summary", Map.of(
                "totalRecipients", personalizedContent.size(),
                "fullyDelivered", successCount,
                "partiallyDelivered", personalizedContent.size() - successCount - failedCount,
                "failed", failedCount,
                "deferred", deferredCount
        ));
        report.put("channelBreakdown", channelSuccessMap);
        report.put("estimatedCost", routing.getOrDefault("estimatedCost", Map.of()));
        report.put("template", Map.of(
                "eventType", template.getOrDefault("eventType", "unknown"),
                "category", template.getOrDefault("category", "unknown"),
                "priority", template.getOrDefault("priority", "medium"),
                "version", template.getOrDefault("templateVersion", "unknown")
        ));
        report.put("_meta", Map.of(
                "pipelineSteps", 5,
                "totalTimeMs", String.format("%.3f", totalMs),
                "totalTimeNanos", endNanos - startNanos,
                "stepTimings", Map.of(
                        "templateResolutionMs", formatMs(afterTemplate - startNanos),
                        "recipientResolutionMs", formatMs(afterRecipients - afterTemplate),
                        "channelRoutingMs", formatMs(afterRouting - afterRecipients),
                        "personalizationMs", formatMs(afterPersonalize - afterRouting),
                        "dispatchMs", formatMs(endNanos - afterPersonalize)
                ),
                "heapObjectsUsed", ctx.heap().keys().size(),
                "zeroCopy", true,
                "note", "5 notification functions composed in-memory. No HTTP calls. No serialization."
        ));

        return KubeFnResponse.ok(report);
    }

    private String formatMs(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }
}
