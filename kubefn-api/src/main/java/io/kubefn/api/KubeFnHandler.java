package io.kubefn.api;

/**
 * Core function handler interface. Every KubeFn function implements this.
 *
 * <p>Functions are independently deployable units that share a living memory space.
 * They can exchange objects at heap speed via {@link HeapExchange}, compose into
 * execution graphs via {@link FnPipeline}, and evolve without restarting the organism.
 */
@FunctionalInterface
public interface KubeFnHandler {

    /**
     * Handle an incoming request and produce a response.
     *
     * @param request the incoming HTTP request
     * @return the response to send back
     * @throws Exception if handling fails
     */
    KubeFnResponse handle(KubeFnRequest request) throws Exception;
}
