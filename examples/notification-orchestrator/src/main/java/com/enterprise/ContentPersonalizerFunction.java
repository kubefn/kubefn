package com.enterprise;

import com.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Personalizes notification content for each recipient and channel.
 * Resolves template variables, applies channel-specific formatting,
 * handles localization, and generates per-recipient content payloads.
 */
@FnRoute(path = "/notify/personalize", methods = {"POST"})
@FnGroup("notification-engine")
public class ContentPersonalizerFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var template = ctx.heap().get("notify:template", Map.class).orElse(Map.of());
        var recipientData = ctx.heap().get("notify:recipients", Map.class).orElse(Map.of());
        var routing = ctx.heap().get("notify:routing", Map.class).orElse(Map.of());

        String subject = (String) template.getOrDefault("subject", "Notification");
        String emailBody = (String) template.getOrDefault("emailBody", "");
        String smsBody = (String) template.getOrDefault("smsBody", "");
        String pushTitle = (String) template.getOrDefault("pushTitle", "");
        String eventType = (String) template.getOrDefault("eventType", "general");

        List<Map<String, Object>> recipients = (List<Map<String, Object>>)
                recipientData.getOrDefault("recipients", List.of());
        List<Map<String, Object>> routingPlan = (List<Map<String, Object>>)
                routing.getOrDefault("routingPlan", List.of());

        // Build variable context from request body
        Map<String, String> variables = extractVariables(request.bodyAsString(), eventType);

        List<Map<String, Object>> personalizedContent = new ArrayList<>();

        for (int i = 0; i < recipients.size(); i++) {
            var recipient = recipients.get(i);
            String userId = (String) recipient.getOrDefault("userId", "unknown");
            String firstName = (String) recipient.getOrDefault("firstName", "Customer");
            String email = (String) recipient.getOrDefault("email", "");

            // Find routing for this recipient
            Map<String, Object> recipientRouting = routingPlan.stream()
                    .filter(r -> userId.equals(r.get("userId")))
                    .findFirst()
                    .orElse(Map.of());

            if (Boolean.TRUE.equals(recipientRouting.get("skipped"))) continue;

            List<Map<String, Object>> channels = (List<Map<String, Object>>)
                    recipientRouting.getOrDefault("channels", List.of());

            // Merge recipient-specific variables
            Map<String, String> recipientVars = new LinkedHashMap<>(variables);
            recipientVars.put("firstName", firstName);
            recipientVars.put("lastName", (String) recipient.getOrDefault("lastName", ""));
            recipientVars.put("email", email);

            // Generate content per channel
            Map<String, Object> channelContent = new LinkedHashMap<>();

            for (var channel : channels) {
                String channelName = (String) channel.get("channel");

                switch (channelName) {
                    case "email" -> {
                        String personalizedSubject = resolveTemplate(subject, recipientVars);
                        String personalizedBody = resolveTemplate(emailBody, recipientVars);
                        // Wrap in HTML for email
                        String htmlBody = wrapEmailHtml(personalizedBody, firstName, eventType);
                        channelContent.put("email", Map.of(
                                "to", email,
                                "subject", personalizedSubject,
                                "bodyText", personalizedBody,
                                "bodyHtml", htmlBody,
                                "replyTo", "noreply@example.com",
                                "unsubscribeUrl", "https://example.com/unsubscribe?user=" + userId
                        ));
                    }
                    case "sms" -> {
                        String personalizedSms = resolveTemplate(smsBody, recipientVars);
                        // Enforce SMS character limit
                        if (personalizedSms.length() > 160) {
                            personalizedSms = personalizedSms.substring(0, 157) + "...";
                        }
                        channelContent.put("sms", Map.of(
                                "to", recipient.getOrDefault("phone", ""),
                                "body", personalizedSms,
                                "characterCount", personalizedSms.length(),
                                "segments", (personalizedSms.length() / 160) + 1
                        ));
                    }
                    case "push" -> {
                        String personalizedTitle = resolveTemplate(pushTitle, recipientVars);
                        String pushBody = resolveTemplate(smsBody, recipientVars); // Reuse short form
                        if (pushBody.length() > 200) {
                            pushBody = pushBody.substring(0, 197) + "...";
                        }
                        channelContent.put("push", Map.of(
                                "title", personalizedTitle,
                                "body", pushBody,
                                "badge", 1,
                                "sound", "critical".equals(template.get("priority")) ? "alert" : "default",
                                "deepLink", buildDeepLink(eventType, recipientVars)
                        ));
                    }
                    case "slack" -> {
                        String personalizedSlack = resolveTemplate(emailBody, recipientVars);
                        channelContent.put("slack", Map.of(
                                "userId", userId,
                                "text", personalizedSlack,
                                "unfurlLinks", false,
                                "iconEmoji", selectSlackIcon(eventType)
                        ));
                    }
                }
            }

            Map<String, Object> personalized = new LinkedHashMap<>();
            personalized.put("userId", userId);
            personalized.put("channelContent", channelContent);
            personalized.put("channelsPersonalized", channelContent.size());
            personalizedContent.add(personalized);
        }

        // Compute content statistics
        long totalEmailContent = personalizedContent.stream()
                .filter(p -> ((Map<?, ?>) p.get("channelContent")).containsKey("email")).count();
        long totalSmsContent = personalizedContent.stream()
                .filter(p -> ((Map<?, ?>) p.get("channelContent")).containsKey("sms")).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("personalizedContent", personalizedContent);
        result.put("recipientsPersonalized", personalizedContent.size());
        result.put("contentStats", Map.of(
                "emailGenerated", totalEmailContent,
                "smsGenerated", totalSmsContent,
                "variablesResolved", variables.size()
        ));

        ctx.heap().publish("notify:personalized", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private String resolveTemplate(String template, Map<String, String> variables) {
        String result = template;
        for (var entry : variables.entrySet()) {
            result = result.replace("#{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private Map<String, String> extractVariables(String body, String eventType) {
        Map<String, String> vars = new LinkedHashMap<>();
        // Common variables with defaults
        vars.put("appName", extractField(body, "appName", "KubeFn Platform"));
        vars.put("orderId", extractField(body, "orderId", "ORD-" + System.nanoTime()));
        vars.put("total", extractField(body, "total", "$99.99"));
        vars.put("itemCount", extractField(body, "itemCount", "1"));
        vars.put("deliveryDate", extractField(body, "deliveryDate", "2026-03-25"));
        vars.put("trackingNumber", extractField(body, "trackingNumber", "TRK-" + System.nanoTime()));
        vars.put("carrier", extractField(body, "carrier", "FedEx"));
        vars.put("failureReason", extractField(body, "failureReason", "card_declined"));
        vars.put("alertType", extractField(body, "alertType", "new_login"));
        vars.put("location", extractField(body, "location", "San Francisco, CA"));
        vars.put("timestamp", extractField(body, "timestamp", java.time.Instant.now().toString()));
        vars.put("planName", extractField(body, "planName", "Professional"));
        vars.put("price", extractField(body, "price", "29.99"));
        vars.put("interval", extractField(body, "interval", "month"));
        vars.put("renewalDate", extractField(body, "renewalDate", "2026-04-01"));
        vars.put("cardLast4", extractField(body, "cardLast4", "4242"));
        vars.put("accountType", extractField(body, "accountType", "standard"));
        vars.put("gettingStartedUrl", extractField(body, "gettingStartedUrl", "https://docs.example.com/start"));
        return vars;
    }

    private String wrapEmailHtml(String body, String firstName, String eventType) {
        return "<div style=\"font-family:Arial,sans-serif;max-width:600px;margin:0 auto;\">"
                + "<h2 style=\"color:#333;\">" + capitalizeEventType(eventType) + "</h2>"
                + "<p>" + body.replace("\n", "<br>") + "</p>"
                + "<hr style=\"border:none;border-top:1px solid #eee;margin:20px 0;\">"
                + "<p style=\"font-size:12px;color:#999;\">You received this because you have an active account.</p>"
                + "</div>";
    }

    private String capitalizeEventType(String eventType) {
        return eventType.replace(".", " - ").replace("_", " ")
                .substring(0, 1).toUpperCase() + eventType.replace(".", " - ").replace("_", " ").substring(1);
    }

    private String buildDeepLink(String eventType, Map<String, String> vars) {
        return switch (eventType) {
            case "order.confirmed", "order.shipped" -> "app://orders/" + vars.getOrDefault("orderId", "");
            case "payment.failed" -> "app://payments/update";
            case "account.security" -> "app://security/review";
            default -> "app://home";
        };
    }

    private String selectSlackIcon(String eventType) {
        return switch (eventType) {
            case "order.confirmed" -> ":white_check_mark:";
            case "order.shipped" -> ":package:";
            case "payment.failed" -> ":warning:";
            case "account.security" -> ":lock:";
            default -> ":bell:";
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
