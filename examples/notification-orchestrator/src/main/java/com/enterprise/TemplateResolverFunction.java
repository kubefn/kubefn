package com.enterprise;

import io.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves notification templates by event type and locale. Loads the
 * template with subject, body, and channel-specific variants, then
 * publishes to HeapExchange for downstream personalization.
 */
@FnRoute(path = "/notify/template", methods = {"POST"})
@FnGroup("notification-engine")
public class TemplateResolverFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Template registry keyed by event type
    private static final Map<String, Map<String, Object>> TEMPLATES = Map.ofEntries(
            Map.entry("order.confirmed", template(
                    "Order Confirmed - #{orderId}",
                    "Hi #{firstName}, your order #{orderId} has been confirmed! " +
                            "We're preparing #{itemCount} item(s) totaling #{total}. " +
                            "Estimated delivery: #{deliveryDate}.",
                    "Your order #{orderId} is confirmed! #{itemCount} item(s), #{total}. " +
                            "Delivery by #{deliveryDate}.",
                    "Order #{orderId} confirmed - #{total}",
                    "informational", "medium"
            )),
            Map.entry("order.shipped", template(
                    "Your Order #{orderId} Has Shipped!",
                    "Great news, #{firstName}! Your order #{orderId} is on its way. " +
                            "Tracking number: #{trackingNumber}. " +
                            "Carrier: #{carrier}. Expected delivery: #{deliveryDate}.",
                    "#{firstName}, order #{orderId} shipped! Track: #{trackingNumber}",
                    "Shipped: #{orderId} via #{carrier}",
                    "informational", "high"
            )),
            Map.entry("payment.failed", template(
                    "Payment Failed for Order #{orderId}",
                    "Hi #{firstName}, we were unable to process your payment of #{total} " +
                            "for order #{orderId}. Reason: #{failureReason}. " +
                            "Please update your payment method within 48 hours to avoid cancellation.",
                    "Payment of #{total} failed for order #{orderId}. " +
                            "Please update payment method. Reason: #{failureReason}",
                    "URGENT: Payment failed - #{orderId}",
                    "action_required", "critical"
            )),
            Map.entry("account.security", template(
                    "Security Alert - #{alertType}",
                    "Hi #{firstName}, we detected #{alertType} on your account from " +
                            "#{location} at #{timestamp}. If this wasn't you, please " +
                            "secure your account immediately by changing your password.",
                    "Security alert: #{alertType} detected from #{location}. " +
                            "Change your password if this wasn't you.",
                    "SECURITY: #{alertType} from #{location}",
                    "security", "critical"
            )),
            Map.entry("subscription.renewal", template(
                    "Your #{planName} Subscription Renews Soon",
                    "Hi #{firstName}, your #{planName} subscription ($#{price}/#{interval}) " +
                            "will renew on #{renewalDate}. Your payment method ending in " +
                            "#{cardLast4} will be charged.",
                    "#{planName} renews #{renewalDate}: $#{price}/#{interval}",
                    "Renewal: #{planName} on #{renewalDate}",
                    "billing", "low"
            )),
            Map.entry("welcome", template(
                    "Welcome to #{appName}, #{firstName}!",
                    "Hi #{firstName}, welcome to #{appName}! We're excited to have you on board. " +
                            "Here are some tips to get started: #{gettingStartedUrl}. " +
                            "Your account type: #{accountType}.",
                    "Welcome to #{appName}, #{firstName}! Get started: #{gettingStartedUrl}",
                    "Welcome to #{appName}!",
                    "onboarding", "medium"
            ))
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String body = request.bodyAsString();
        String eventType = extractField(body, "eventType", "order.confirmed");
        String locale = extractField(body, "locale", "en-US");

        Map<String, Object> tmpl = TEMPLATES.getOrDefault(eventType, TEMPLATES.get("order.confirmed"));

        // Enrich template with resolution metadata
        Map<String, Object> resolved = new LinkedHashMap<>(tmpl);
        resolved.put("eventType", eventType);
        resolved.put("locale", locale);
        resolved.put("templateVersion", "v2.1");
        resolved.put("resolvedAt", System.currentTimeMillis());

        // Extract variable placeholders from template for validation
        String emailBody = (String) tmpl.get("emailBody");
        var placeholders = new java.util.ArrayList<String>();
        int idx = 0;
        while ((idx = emailBody.indexOf("#{", idx)) >= 0) {
            int end = emailBody.indexOf("}", idx);
            if (end > idx) {
                placeholders.add(emailBody.substring(idx + 2, end));
                idx = end + 1;
            } else break;
        }
        resolved.put("requiredVariables", placeholders);

        ctx.heap().publish("notify:template", resolved, Map.class);

        return KubeFnResponse.ok(resolved);
    }

    private static Map<String, Object> template(String subject, String emailBody,
                                                  String smsBody, String pushTitle,
                                                  String category, String priority) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("subject", subject);
        t.put("emailBody", emailBody);
        t.put("smsBody", smsBody);
        t.put("pushTitle", pushTitle);
        t.put("category", category);
        t.put("priority", priority);
        return t;
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
