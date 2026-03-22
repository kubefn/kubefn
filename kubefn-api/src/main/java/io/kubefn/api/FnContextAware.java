package io.kubefn.api;

/**
 * Implement this alongside {@link KubeFnHandler} to receive the function context.
 * The context provides access to HeapExchange, FnPipeline, cache, logging, and config.
 */
public interface FnContextAware {

    /**
     * Called by the runtime after instantiation, before any requests are routed.
     *
     * @param context the function context for this group/revision
     */
    void setContext(FnContext context);
}
