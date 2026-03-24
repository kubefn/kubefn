# Architecture

## The Organism Model

A KubeFn **organism** is a single JVM process running multiple functions. Functions are independently developed, built, and deployed -- but they share a heap at runtime.

```
┌─────────────────────────────────────────────────┐
│                  KubeFn Organism                │
│                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐     │
│  │ Pricing  │  │   Tax    │  │Inventory │     │
│  │ Function │  │ Function │  │ Function │     │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘     │
│       │              │              │           │
│  ─────┴──────────────┴──────────────┴─────      │
│  │            HeapExchange                │      │
│  │  ┌─────────────┐ ┌─────────────────┐  │      │
│  │  │PricingResult│ │InventoryStatus  │  │      │
│  │  └─────────────┘ └─────────────────┘  │      │
│  ─────────────────────────────────────────      │
│                                                 │
│  ┌───────────────────────────────────────┐      │
│  │         Netty HTTP Server             │      │
│  └───────────────────────────────────────┘      │
└─────────────────────────────────────────────────┘
```

## HeapExchange

The shared object graph. Functions publish and read Java objects by typed key. No serialization -- the consumer gets the same object reference the producer published.

## Classloader Isolation

Each `@FnGroup` gets its own classloader. Functions in the same group share classes. Functions in different groups are isolated -- they can only share objects through contract types in `kubefn-contracts`.

## The Layered Classpath

```
Layer 1: kubefn-api        → KubeFnHandler, FnContext, HeapExchange (interfaces)
Layer 2: kubefn-contracts   → PricingResult, AuthContext, HeapKeys (shared types)
Layer 3: kubefn-shared      → HeapReader, HeapPublisher, PipelineBuilder (utilities)
Layer 4: your-function.jar  → Your code (thin JAR, no framework)
```

Layers 1-3 are provided by the runtime. Your JAR only contains Layer 4. This is why functions are small (KB, not MB) and load fast.

## Request Flow

1. HTTP request hits Netty
2. Route matcher finds the function by `@FnRoute`
3. Function's classloader loads and invokes `handle()`
4. Function reads/writes HeapExchange as needed
5. Response returns through Netty

No framework boot. No dependency injection container. No classpath scanning.

## Next

- [HeapExchange deep dive](heap-exchange.md)
- [Function Model](function-model.md)
