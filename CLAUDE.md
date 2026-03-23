# KubeFn Development Rules

## What is KubeFn
A shared-memory function platform. Functions share a JVM heap via HeapExchange — zero-copy, zero-serialization object sharing between independently deployed functions.

## Project Structure
```
kubefn-api/          → Function author API (KubeFnHandler, HeapExchange, FnContext)
kubefn-contracts/    → Shared type definitions for heap objects (like Protobuf)
kubefn-runtime/      → The runtime (Netty, classloading, HeapExchange impl)
examples/            → Function examples
```

## How to Create a Function

### 1. Implement KubeFnHandler
```java
@FnRoute(path = "/my/endpoint", methods = {"GET", "POST"})
@FnGroup("my-service")
public class MyFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // Your logic here
        return KubeFnResponse.ok(Map.of("result", "hello"));
    }
}
```

### 2. Read from HeapExchange (zero-copy)
```java
// Use contract types + HeapKeys constants — NEVER hardcode key strings
PricingResult pricing = ctx.heap()
    .get(HeapKeys.PRICING_CURRENT, PricingResult.class)
    .orElseThrow(() -> new IllegalStateException("Pricing not on heap"));
```

### 3. Publish to HeapExchange
```java
var result = new TaxCalculation(subtotal, 0.0825, taxAmount, total);
ctx.heap().publish(HeapKeys.TAX_CALCULATED, result, TaxCalculation.class);
```

## HeapExchange API
```java
ctx.heap().publish(key, value, Type.class)  // Publish object (zero-copy)
ctx.heap().get(key, Type.class)             // Read object → Optional<T>
ctx.heap().remove(key)                       // Remove object
ctx.heap().keys()                            // List all keys
ctx.heap().contains(key)                     // Check if key exists
```

## Available Contract Types (kubefn-contracts)
| Type | Heap Key | Published By | Description |
|------|----------|-------------|-------------|
| `AuthContext` | `auth:{userId}` | Auth functions | User identity, roles, permissions |
| `PricingResult` | `pricing:current` | Pricing functions | Base price, discount, final price |
| `InventoryStatus` | `inventory:{sku}` | Inventory functions | Stock levels, warehouse |
| `FraudScore` | `fraud:result` | Fraud functions | Risk score, approved flag |
| `ShippingEstimate` | `shipping:estimate` | Shipping functions | Method, cost, ETA |
| `TaxCalculation` | `tax:calculated` | Tax functions | Subtotal, rate, total |

Use `HeapKeys` class for all key constants.

## Critical Rules
1. **NEVER serialize/deserialize heap objects** — they're shared references, not messages
2. **ALWAYS use contract types** from `com.kubefn.contracts` — never raw `Map`
3. **ALWAYS use `HeapKeys`** constants — never hardcode key strings
4. **ALWAYS handle `Optional.empty()`** — the producer might not have run yet
5. **NEVER mutate objects read from heap** — treat them as immutable
6. **Functions are stateless** — all shared state goes through HeapExchange

## Common Patterns

### Producer (publishes to heap)
```java
var result = new PricingResult("USD", 99.99, 0.15, 84.99);
ctx.heap().publish(HeapKeys.PRICING_CURRENT, result, PricingResult.class);
```

### Consumer (reads from heap)
```java
PricingResult pricing = ctx.heap()
    .get(HeapKeys.PRICING_CURRENT, PricingResult.class)
    .orElse(new PricingResult("USD", 0, 0, 0)); // graceful fallback
```

### Pipeline (call sibling functions)
```java
ctx.getFunction(PricingFunction.class).handle(request);
ctx.getFunction(TaxFunction.class).handle(request);
// Results are on the heap — read them zero-copy
```

## Dependencies (layered classpath — compile against, runtime provides)
```gradle
compileOnly("com.kubefn:kubefn-api:0.4.0")        // Layer 1: interfaces
compileOnly("com.kubefn:kubefn-contracts:0.4.0")   // Layer 2: shared types
compileOnly("com.kubefn:kubefn-shared:0.4.0")      // Layer 3: utilities
// Layer 4: YOUR code only (thin JAR)
```

## Shared Utilities (kubefn-shared)
```java
// Easy heap reads
PricingResult pricing = HeapReader.require(ctx, HeapKeys.PRICING_CURRENT, PricingResult.class);
PricingResult pricing = HeapReader.getOrDefault(ctx, key, PricingResult.class, () -> defaultPricing);

// Easy heap writes
HeapPublisher.publish(ctx, HeapKeys.TAX_CALCULATED, taxResult);

// Easy pipeline orchestration
var result = PipelineBuilder.create(ctx, request)
    .step("auth", AuthFunction.class)
    .step("pricing", PricingFunction.class)
    .step("tax", TaxFunction.class)
    .execute();
```

## Reference Examples (study these)
| Pattern | File | What it teaches |
|---|---|---|
| Producer | `examples/patterns/producer/ProductCatalogProducer.java` | Publishing typed objects to HeapExchange |
| Consumer | `examples/patterns/consumer/OrderSummaryConsumer.java` | Reading multiple typed objects, zero-copy |
| Fallback | `examples/patterns/consumer-with-fallback/ResilientConsumer.java` | Graceful degradation when data missing |
| Pipeline | `examples/patterns/pipeline-orchestrator/CheckoutOrchestrator.java` | Calling siblings + assembling from heap |
| Contract-First | `examples/patterns/contract-first-stub/` | Developer A+B parallel development |

## Commands
```bash
kubefn init my-function my-group    # Scaffold new function
kubefn dev                          # Local dev with hot-reload
kubefn deploy jar group             # Deploy to organism
kubefn functions                    # List deployed functions
kubefn heap                         # Inspect HeapExchange
kubefn status                       # Runtime status
```
