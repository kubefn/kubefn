# Typed HeapKeys

## Why Typed Keys Matter

Raw string keys are error-prone. A typo in `"pricing:current"` silently returns `Optional.empty()`. Typed keys catch errors at compile time.

```java
// BAD: raw string, no type safety
ctx.heap().get("pricing:curent", PricingResult.class); // typo, silent failure

// GOOD: typed constant, compile-time checked
ctx.heap().get(HeapKeys.PRICING_CURRENT, PricingResult.class); // won't compile if wrong
```

## Static Keys

Defined in `kubefn-contracts` for well-known heap entries:

```java
public final class HeapKeys {
    public static final String PRICING_CURRENT = "pricing:current";
    public static final String TAX_CALCULATED = "tax:calculated";
    public static final String FRAUD_RESULT = "fraud:result";
    public static final String SHIPPING_ESTIMATE = "shipping:estimate";
    // ...
}
```

**Rule: never hardcode key strings in function code.** Always use `HeapKeys` constants.

## Dynamic Keys

For per-entity data, use key factories:

```java
public final class HeapKeys {
    // Static
    public static final String PRICING_CURRENT = "pricing:current";

    // Dynamic
    public static String authContext(String userId) {
        return "auth:" + userId;
    }

    public static String inventoryStatus(String sku) {
        return "inventory:" + sku;
    }
}
```

Usage:

```java
// Publish auth for a specific user
ctx.heap().publish(
    HeapKeys.authContext(userId),
    authCtx,
    AuthContext.class
);

// Read inventory for a specific SKU
InventoryStatus inv = ctx.heap()
    .get(HeapKeys.inventoryStatus("SKU-1234"), InventoryStatus.class)
    .orElseThrow();
```

## The Contracts Module Pattern

All heap key constants and shared types live in `kubefn-contracts`:

```
kubefn-contracts/
  src/main/java/com/kubefn/contracts/
    HeapKeys.java              # All key constants
    types/
      PricingResult.java       # Shared record types
      AuthContext.java
      InventoryStatus.java
      TaxCalculation.java
```

Functions depend on `kubefn-contracts` as `compileOnly`. The runtime provides it at Layer 2.

## Next

- [Function Model](function-model.md)
- [HeapExchange deep dive](heap-exchange.md)
