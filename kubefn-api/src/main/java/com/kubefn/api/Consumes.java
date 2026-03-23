package com.kubefn.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which HeapExchange keys this function reads.
 * Self-documenting + enables build-time validation + dependency graph.
 *
 * <pre>{@code
 * @FnRoute(path = "/tax/calculate", methods = {"POST"})
 * @FnGroup("checkout-service")
 * @Consumes({"pricing:current", "auth:*"})
 * public class TaxFunction implements KubeFnHandler { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Consumes {
    /** HeapExchange key patterns this function reads. */
    String[] value();
}
