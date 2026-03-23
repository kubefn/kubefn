# Pattern 5: Contract-First Stub

This pattern demonstrates how two developers can work in parallel by agreeing
on a contract type first, then implementing producer and consumer independently.

## The Workflow

1. **Developer A and B agree on a contract type** — `LoyaltyPoints` record
   with fields: `userId`, `points`, `tier`, `multiplier`.

2. **Developer B codes against the contract immediately** — `LoyaltyConsumer`
   reads `LoyaltyPoints` from HeapExchange with a `.orElse()` fallback. B's
   function works even BEFORE A has deployed, because the fallback provides
   sensible defaults.

3. **Developer A implements the producer** — `LoyaltyProducer` creates real
   `LoyaltyPoints` data and publishes it to heap.

4. **When A deploys, B's code works unchanged** — zero modifications needed.
   The `.orElse()` fallback is never triggered once the producer is live.

## Why This Works

- The contract type (`LoyaltyPoints` record) is the shared interface
- HeapExchange is the transport mechanism — both sides code against it
- `Optional.orElse()` is the compatibility bridge during parallel development
- No mocks, no stubs, no feature flags — just typed contracts and fallbacks

## Key Files

- `NewFeatureContract.java` — The agreed-upon contract type
- `LoyaltyProducer.java` — Developer A's producer (can be deployed later)
- `LoyaltyConsumer.java` — Developer B's consumer (works immediately)
