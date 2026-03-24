# HeapExchange

HeapExchange is the shared object graph at the core of KubeFn. Functions publish and read Java objects by typed key -- zero-copy, zero-serialization.

## Zero-Copy Explained

When Function A publishes an object:

```java
var pricing = new PricingResult("USD", 99.99, 0.15, 84.99);
ctx.heap().publish(HeapKeys.PRICING_CURRENT, pricing, PricingResult.class);
```

Function B gets the **same object reference**:

```java
PricingResult p = ctx.heap().get(HeapKeys.PRICING_CURRENT, PricingResult.class).get();
// p is the SAME object in memory. No copy. No deserialization.
```

This is possible because both functions run in the same JVM and share contract types via the layered classpath.

## Core API

```java
// Publish an object
ctx.heap().publish(key, value, Type.class);

// Read an object (returns Optional<T>)
Optional<T> result = ctx.heap().get(key, Type.class);

// Read or throw
T result = HeapReader.require(ctx, key, Type.class);

// Read with fallback
T result = HeapReader.getOrDefault(ctx, key, Type.class, () -> defaultValue);

// Remove
ctx.heap().remove(key);

// Query
boolean exists = ctx.heap().contains(key);
Set<String> allKeys = ctx.heap().keys();
```

## HeapCapsule

Every heap entry is wrapped in a `HeapCapsule` containing metadata:

| Field | Type | Description |
|-------|------|-------------|
| `value` | `T` | The stored object |
| `version` | `long` | Monotonically increasing version |
| `publisher` | `String` | Function class that published it |
| `timestamp` | `Instant` | When it was published |
| `type` | `Class<T>` | Runtime type of the value |

## Immutability Rule

**Never mutate an object read from the heap.** Other functions hold the same reference. If you need to change it, create a new object and publish it.

```java
// WRONG: mutates shared state
PricingResult p = ctx.heap().get(key, PricingResult.class).get();
p.setDiscount(0.20); // other readers now see the mutation

// RIGHT: publish a new object
PricingResult p = ctx.heap().get(key, PricingResult.class).get();
var updated = new PricingResult(p.currency(), p.basePrice(), 0.20, newFinal);
ctx.heap().publish(key, updated, PricingResult.class);
```

## Schema Evolution

Contract types live in `kubefn-contracts`. To evolve a schema:

1. Add new fields with defaults (backward compatible)
2. Deploy updated contracts JAR to the runtime
3. Deploy updated functions

Never remove fields from contract types without coordinating all consumers.

## Next

- [Typed HeapKeys](heap-keys.md)
- [HeapExchange API Reference](../reference/heap-api.md)
