package io.kubefn.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which function group this handler belongs to.
 * Functions in the same group share a classloader, HeapExchange, cache,
 * and can compose into FnGraphs together.
 *
 * <pre>{@code
 * @FnGroup("checkout-service")
 * public class PricingFunction implements KubeFnHandler { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FnGroup {

    /** The group name (e.g., "checkout-service"). */
    String value();
}
