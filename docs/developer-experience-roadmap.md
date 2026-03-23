# KubeFn Developer Experience Roadmap

## Problem: KubeFn is easier for AI agents than for human developers

AI agents thrive on rigid patterns. Humans need visibility, feedback, and forgiveness.

## The 7 Features That Make KubeFn Easy for Humans

### 1. Smart Error Messages (build NOW)

**Before:**
```
Optional.empty()
```

**After:**
```
HeapExchange: key 'pricing:current' not found.
  Expected type: PricingResult
  Produced by: checkout-pipeline.PricingFunction
  Status: PricingFunction has not been called in this request.
  Hint: Call PricingFunction before TaxFunction, or use .orElse() for a default.
```

Implementation: When `heap.get()` returns empty, check the SchemaRegistry
for who produces that key, check if that producer has been invoked in
this request (via causal trace), and generate a helpful message.

### 2. `kubefn dev` with Live Heap Dashboard (build soon)

When running locally, open `http://localhost:8081/admin/ui` and see:

```
┌─────────────────────────────────────────────┐
│ HeapExchange — Live State                    │
│                                               │
│ pricing:current    PricingResult    2s ago    │
│   basePrice: 99.99                            │
│   discount: 0.15                              │
│   finalPrice: 84.99                           │
│                                               │
│ auth:user-001      AuthContext      5s ago    │
│   userId: user-001                            │
│   tier: premium                               │
│   roles: [user, admin]                        │
│                                               │
│ [empty] tax:calculated — waiting for TaxFn   │
└─────────────────────────────────────────────┘
```

Developers can SEE what's on the heap in real-time.
Click any object to see its full content, producer, and consumers.

### 3. Annotations That Document Intent (build soon)

Instead of just `@FnRoute` and `@FnGroup`, add optional heap annotations:

```java
@FnRoute(path = "/tax/calculate", methods = {"POST"})
@FnGroup("checkout-service")
@Consumes({"pricing:current", "auth:*"})
@Produces({"tax:calculated"})
public class TaxFunction implements KubeFnHandler {
```

Benefits:
- Self-documenting: any developer reading the class knows what it needs
- IDE can show "go to producer" / "go to consumer"
- Build-time validation: warn if consumed key has no known producer
- Auto-generate function dependency graph
- AI agents use these for correct code generation

### 4. Compile-Time Heap Contract Validation (build later)

Annotation processor that checks at build time:
- Every `@Consumes` key has a known `@Produces` in the codebase
- Types match between producer and consumer
- No circular dependencies
- Schema version compatibility

```
[WARNING] TaxFunction @Consumes("pricing:current") but no function
          in this build @Produces("pricing:current").
          Expected producer: checkout-pipeline.PricingFunction
```

### 5. `kubefn playground` — Interactive REPL (build later)

```bash
$ kubefn playground
KubeFn Playground v0.4.0 — connected to local organism

> heap.keys()
["pricing:current", "auth:user-001", "inventory:PROD-42"]

> heap.get("pricing:current")
PricingResult { currency="USD", basePrice=99.99, discount=0.15, finalPrice=84.99 }
  Published by: checkout-pipeline.PricingFunction
  Published at: 2s ago
  Version: 3

> heap.publish("test:data", {"hello": "world"})
Published: test:data (version 4)

> call("/tax/calculate")
TaxCalculation { subtotal=84.99, taxRate=0.0825, taxAmount=7.01, total=92.00 }
  Duration: 0.12ms

> trace.last()
  1. PricingFunction   0.05ms  → pricing:current
  2. TaxFunction       0.12ms  → tax:calculated (reads pricing:current)
```

This is the single best onboarding tool. A developer can explore the
organism interactively before writing a single line of code.

### 6. Function Dependency Graph (build later)

```bash
$ kubefn graph

AuthFunction ──publishes──> auth:user-001 ──consumed by──> FraudFunction
PricingFunction ──publishes──> pricing:current ──consumed by──> TaxFunction, FraudFunction
TaxFunction ──publishes──> tax:calculated ──consumed by──> CheckoutAssembler
FraudFunction ──publishes──> fraud:result ──consumed by──> CheckoutAssembler
```

Visual representation of how functions connect through the heap.
Makes the invisible data flow visible.

### 7. Guided Scaffolding with Discovery (build later)

```bash
$ kubefn scaffold function
? What does your function do? Calculate shipping costs
? What data do you need from the heap?
  [x] pricing:current (PricingResult) — produced by PricingFunction
  [x] inventory:PROD-42 (InventoryStatus) — produced by InventoryFunction
  [ ] auth:* (AuthContext) — produced by AuthFunction
? What will your function publish to the heap?
  Key: shipping:estimate
  Type: ShippingEstimate

Generating ShippingFunction.java...
  ✓ @Consumes({"pricing:current", "inventory:PROD-42"})
  ✓ @Produces({"shipping:estimate"})
  ✓ Typed heap reads with fallbacks
  ✓ Test with mock fixtures
  ✓ kubefn.yaml manifest
Done. Run `kubefn dev` to test.
```

Interactive scaffolding that queries the live organism to show what's
available, lets you pick what you need, and generates correct code.

## Priority Order

1. **Smart error messages** — cheapest, biggest impact on debugging
2. **@Consumes/@Produces annotations** — self-documenting, enables tooling
3. **Live heap dashboard in trace UI** — makes the invisible visible
4. **Compile-time validation** — catches errors before runtime
5. **kubefn playground** — best onboarding tool
6. **Function dependency graph** — organizational clarity
7. **Guided scaffolding** — reduces time-to-first-function

## The Principle

> **Make the heap as visible and discoverable as an HTTP API.**

In microservices, you have Swagger UI, curl, Postman.
In KubeFn, you need the equivalent for shared memory.
