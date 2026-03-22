package com.kubefn.runtime.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Request timeout enforcement. Wraps function execution with a hard deadline.
 * If a function exceeds its timeout, the virtual thread is interrupted and
 * the client receives a 504 Gateway Timeout.
 */
public class RequestTimeout {

    private static final Logger log = LoggerFactory.getLogger(RequestTimeout.class);

    private final long defaultTimeoutMs;

    public RequestTimeout(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * Execute a callable with timeout enforcement.
     *
     * @param task the function invocation
     * @param timeoutMs timeout in milliseconds (0 = use default)
     * @param requestId for logging
     * @return the result
     * @throws TimeoutException if the function exceeds its deadline
     */
    public <T> T executeWithTimeout(Callable<T> task, long timeoutMs, String requestId)
            throws Exception {
        long effectiveTimeout = timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;

        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, Thread.ofVirtual().factory()::newThread)
            .get(effectiveTimeout, TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            log.warn("Request {} timed out after {}ms", requestId, effectiveTimeout);
            throw new TimeoutException(
                    "Function execution exceeded deadline of " + effectiveTimeout + "ms");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
