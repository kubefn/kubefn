package com.example;

import io.kubefn.api.*;

import java.util.Map;

/**
 * Example function demonstrating KubeFn basics.
 * Greets the user and publishes the greeting to HeapExchange
 * for other functions to consume zero-copy.
 */
@FnRoute(path = "/greet", methods = {"GET", "POST"})
@FnGroup("hello-service")
public class GreetFunction implements KubeFnHandler, FnContextAware, FnLifecycle {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public void onInit() {
        ctx.logger().info("GreetFunction initialized in group '{}' rev '{}'",
                ctx.groupName(), ctx.revisionId());
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) {
        String name = request.queryParam("name").orElse("World");

        // Create greeting object
        Map<String, Object> greeting = Map.of(
                "message", "Hello, " + name + "!",
                "group", ctx.groupName(),
                "revision", ctx.revisionId()
        );

        // Publish to HeapExchange — other functions can read this
        // ZERO COPY. Same object in memory.
        ctx.heap().publish("lastGreeting", greeting, Map.class);

        // Also cache it
        ctx.cache().put("greet:" + name, greeting);

        return KubeFnResponse.ok(greeting);
    }
}
