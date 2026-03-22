package io.kubefn.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the HTTP route for a function handler.
 *
 * <pre>{@code
 * @FnRoute(path = "/checkout/price", methods = {"POST"})
 * public class PricingFunction implements KubeFnHandler { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FnRoute {

    /** The URL path this function handles (e.g., "/greet"). */
    String path();

    /** HTTP methods this function accepts. Defaults to GET and POST. */
    String[] methods() default {"GET", "POST"};
}
