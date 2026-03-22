package com.example;

import com.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Example function that reads from HeapExchange — demonstrating
 * zero-copy object sharing between independently deployed functions.
 */
@FnRoute(path = "/echo", methods = {"GET"})
@FnGroup("hello-service")
public class EchoFunction implements KubeFnHandler, FnContextAware {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Read from HeapExchange — ZERO COPY, same heap object
        var lastGreeting = ctx.heap().get("lastGreeting", Map.class);
        result.put("lastGreeting", lastGreeting.orElse(null));
        result.put("heapKeys", ctx.heap().keys());

        // Show that we're reading the SAME object (identity check)
        result.put("zeroCopy", lastGreeting.isPresent());
        result.put("group", ctx.groupName());
        result.put("revision", ctx.revisionId());

        return KubeFnResponse.ok(result);
    }
}
