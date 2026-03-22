package com.enterprise;

import com.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verifies the sender has sufficient funds for the payment including
 * any FX fees. Checks available balance, pending holds, and daily
 * transaction limits.
 */
@FnRoute(path = "/pay/balance", methods = {"POST"})
@FnGroup("payment-pipeline")
public class BalanceCheckFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var payment = ctx.heap().get("payment:validated", Map.class).orElse(Map.of());
        var fx = ctx.heap().get("payment:fx", Map.class).orElse(Map.of());

        String senderId = (String) payment.getOrDefault("senderId", "unknown");
        double paymentAmount = ((Number) payment.getOrDefault("amount", 0.0)).doubleValue();
        String currency = (String) payment.getOrDefault("currency", "USD");

        // Calculate total debit including FX fees
        double fxFee = 0.0;
        if (Boolean.TRUE.equals(fx.get("required"))) {
            var fees = (Map<String, Object>) fx.getOrDefault("fees", Map.of());
            fxFee = ((Number) fees.getOrDefault("fxFeeAmount", 0.0)).doubleValue();
        }
        double totalDebit = paymentAmount + fxFee;

        // Simulate account lookup (deterministic based on sender ID)
        long hash = Math.abs(senderId.hashCode());
        double ledgerBalance = 10_000 + (hash % 90_000); // $10K - $100K
        double pendingDebits = (hash % 5000); // Pending holds
        double pendingCredits = (hash % 2000); // Incoming
        double availableBalance = ledgerBalance - pendingDebits + pendingCredits;

        // Daily limit check
        double dailyLimit = 250_000.0;
        double todaySpent = (hash % 50_000); // Simulated today's spend
        double remainingDailyLimit = dailyLimit - todaySpent;

        // Evaluate sufficiency
        boolean hasSufficientFunds = availableBalance >= totalDebit;
        boolean withinDailyLimit = totalDebit <= remainingDailyLimit;
        boolean approved = hasSufficientFunds && withinDailyLimit;

        // Compute headroom
        double balanceHeadroom = availableBalance - totalDebit;
        double dailyLimitHeadroom = remainingDailyLimit - totalDebit;

        String status;
        String reason = "";
        if (!hasSufficientFunds) {
            status = "INSUFFICIENT_FUNDS";
            reason = String.format("Required $%.2f but available balance is $%.2f (shortfall: $%.2f)",
                    totalDebit, availableBalance, totalDebit - availableBalance);
        } else if (!withinDailyLimit) {
            status = "DAILY_LIMIT_EXCEEDED";
            reason = String.format("Payment $%.2f exceeds remaining daily limit of $%.2f",
                    totalDebit, remainingDailyLimit);
        } else {
            status = "APPROVED";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("senderId", senderId);
        result.put("status", status);
        result.put("approved", approved);
        result.put("balances", Map.of(
                "currency", currency,
                "ledgerBalance", round(ledgerBalance),
                "pendingDebits", round(pendingDebits),
                "pendingCredits", round(pendingCredits),
                "availableBalance", round(availableBalance)
        ));
        result.put("debitBreakdown", Map.of(
                "paymentAmount", round(paymentAmount),
                "fxFee", round(fxFee),
                "totalDebit", round(totalDebit)
        ));
        result.put("limits", Map.of(
                "dailyLimit", round(dailyLimit),
                "todaySpent", round(todaySpent),
                "remainingDailyLimit", round(remainingDailyLimit)
        ));
        result.put("headroom", Map.of(
                "balanceHeadroom", round(balanceHeadroom),
                "dailyLimitHeadroom", round(dailyLimitHeadroom)
        ));
        if (!reason.isEmpty()) {
            result.put("reason", reason);
        }

        ctx.heap().publish("payment:balance", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
