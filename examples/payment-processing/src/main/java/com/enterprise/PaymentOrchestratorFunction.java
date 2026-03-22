package com.enterprise;

import com.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orchestrates the full payment processing pipeline. Calls all upstream
 * functions via ctx.getFunction(), implements early-exit on failures
 * (validation, AML block, insufficient funds), and produces a comprehensive
 * payment receipt with timing metadata.
 */
@FnRoute(path = "/pay/process", methods = {"POST"})
@FnGroup("payment-pipeline")
public class PaymentOrchestratorFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long startNanos = System.nanoTime();

        // Step 1: Validate payment request
        ctx.getFunction(PaymentValidationFunction.class).handle(request);
        long afterValidation = System.nanoTime();

        var validation = ctx.heap().get("payment:validated", Map.class).orElse(Map.of());
        if (!Boolean.TRUE.equals(validation.get("valid"))) {
            return buildFailureResponse("VALIDATION_FAILED", validation,
                    startNanos, afterValidation, "Payment validation failed");
        }

        // Step 2: AML/Sanctions screening
        ctx.getFunction(AMLScreeningFunction.class).handle(request);
        long afterAML = System.nanoTime();

        var aml = ctx.heap().get("payment:aml", Map.class).orElse(Map.of());
        if (Boolean.TRUE.equals(aml.get("blocked"))) {
            return buildFailureResponse("AML_BLOCKED", aml,
                    startNanos, afterAML, "Payment blocked by AML screening");
        }

        // Step 3: FX conversion (if cross-border)
        ctx.getFunction(FXConversionFunction.class).handle(request);
        long afterFX = System.nanoTime();

        // Step 4: Balance check
        ctx.getFunction(BalanceCheckFunction.class).handle(request);
        long afterBalance = System.nanoTime();

        var balance = ctx.heap().get("payment:balance", Map.class).orElse(Map.of());
        if (!Boolean.TRUE.equals(balance.get("approved"))) {
            return buildFailureResponse("BALANCE_INSUFFICIENT", balance,
                    startNanos, afterBalance, (String) balance.getOrDefault("reason", "Insufficient funds"));
        }

        // Step 5: Create ledger entries
        ctx.getFunction(LedgerEntryFunction.class).handle(request);
        long afterLedger = System.nanoTime();

        // Read all results from heap
        var fx = ctx.heap().get("payment:fx", Map.class).orElse(Map.of());
        var ledger = ctx.heap().get("payment:ledger", Map.class).orElse(Map.of());

        long endNanos = System.nanoTime();
        double totalMs = (endNanos - startNanos) / 1_000_000.0;

        // Determine final status
        boolean requiresEDD = Boolean.TRUE.equals(aml.get("requiresEDD"));
        String finalStatus = requiresEDD ? "PENDING_REVIEW" : "COMPLETED";

        // Build payment receipt
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("paymentId", validation.get("paymentId"));
        receipt.put("status", finalStatus);
        receipt.put("payment", Map.of(
                "senderId", validation.get("senderId"),
                "receiverId", validation.get("receiverId"),
                "amount", validation.get("amount"),
                "currency", validation.get("currency"),
                "type", validation.get("paymentType"),
                "crossBorder", validation.get("crossBorder"),
                "reference", validation.getOrDefault("reference", "")
        ));

        // Include FX details if applicable
        if (Boolean.TRUE.equals(fx.get("required"))) {
            receipt.put("foreignExchange", Map.of(
                    "sourceCurrency", fx.get("sourceCurrency"),
                    "targetCurrency", fx.get("targetCurrency"),
                    "sourceAmount", fx.get("sourceAmount"),
                    "targetAmount", fx.get("targetAmount"),
                    "customerRate", ((Map<String, Object>) fx.getOrDefault("rates", Map.of())).get("customerRate"),
                    "quoteId", fx.get("quoteId")
            ));
        }

        receipt.put("compliance", Map.of(
                "amlDecision", aml.get("amlDecision"),
                "amlRiskScore", aml.get("riskScore"),
                "requiresEDD", requiresEDD,
                "flagCount", ((java.util.List<?>) aml.getOrDefault("flags", java.util.List.of())).size()
        ));

        receipt.put("accounting", Map.of(
                "journalId", ledger.get("journalId"),
                "ledgerEntries", ledger.get("entryCount"),
                "balanced", ledger.get("balanced"),
                "journalStatus", ledger.get("journalStatus")
        ));

        receipt.put("balanceAfter", Map.of(
                "previousAvailable", ((Map<String, Object>) balance.getOrDefault("balances", Map.of()))
                        .getOrDefault("availableBalance", 0),
                "totalDebited", ((Map<String, Object>) balance.getOrDefault("debitBreakdown", Map.of()))
                        .getOrDefault("totalDebit", 0)
        ));

        receipt.put("_meta", Map.of(
                "pipelineSteps", 6,
                "totalTimeMs", String.format("%.3f", totalMs),
                "totalTimeNanos", endNanos - startNanos,
                "stepTimings", Map.of(
                        "validationMs", formatMs(afterValidation - startNanos),
                        "amlScreeningMs", formatMs(afterAML - afterValidation),
                        "fxConversionMs", formatMs(afterFX - afterAML),
                        "balanceCheckMs", formatMs(afterBalance - afterFX),
                        "ledgerEntryMs", formatMs(afterLedger - afterBalance),
                        "assemblyMs", formatMs(endNanos - afterLedger)
                ),
                "heapObjectsUsed", ctx.heap().keys().size(),
                "zeroCopy", true,
                "note", "6 payment functions composed in-memory. No HTTP calls. No serialization."
        ));

        return KubeFnResponse.ok(receipt);
    }

    private KubeFnResponse buildFailureResponse(String failureCode, Map<String, Object> details,
                                                  long startNanos, long failedAtNanos, String reason) {
        double ms = (failedAtNanos - startNanos) / 1_000_000.0;
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("status", "FAILED");
        failure.put("failureCode", failureCode);
        failure.put("reason", reason);
        failure.put("details", details);
        failure.put("_meta", Map.of(
                "failedAfterMs", String.format("%.3f", ms),
                "earlyExit", true,
                "note", "Pipeline terminated early due to " + failureCode
        ));
        return KubeFnResponse.error(failure);
    }

    private String formatMs(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }
}
