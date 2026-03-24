# Function Model

## KubeFnHandler

The core interface. One method, one responsibility:

```java
public interface KubeFnHandler {
    KubeFnResponse handle(KubeFnRequest request) throws Exception;
}
```

## FnContextAware

Implement this to get heap access and function references:

```java
public interface FnContextAware {
    void setContext(FnContext context);
}
```

The runtime calls `setContext()` before every `handle()` invocation.

## @FnRoute

Maps HTTP paths to functions:

```java
@FnRoute(path = "/api/checkout", methods = {"POST"})
```

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `path` | `String` | required | URL path |
| `methods` | `String[]` | `{"GET"}` | HTTP methods |

## @FnGroup

Assigns classloader isolation:

```java
@FnGroup("checkout")
```

Functions in the same group share a classloader. Different groups are isolated and can only communicate through contract types on the heap.

## Request/Response

```java
// Request
String body = request.body();
MyType parsed = request.bodyAs(MyType.class);
String param = request.queryParam("id");
String header = request.header("Authorization");
String method = request.method();
String path = request.path();

// Response
KubeFnResponse.ok(body)                    // 200
KubeFnResponse.created(body)               // 201
KubeFnResponse.badRequest("invalid input") // 400
KubeFnResponse.notFound()                  // 404
KubeFnResponse.error("failed")             // 500
KubeFnResponse.of(statusCode, body)        // custom
```

## Calling Sibling Functions

Functions can invoke other functions in the same organism without HTTP:

```java
@Override
public KubeFnResponse handle(KubeFnRequest request) throws Exception {
    // Call siblings -- they publish results to the heap
    ctx.getFunction(PricingFunction.class).handle(request);
    ctx.getFunction(TaxFunction.class).handle(request);
    ctx.getFunction(InventoryFunction.class).handle(request);

    // Read results from heap (zero-copy)
    PricingResult pricing = HeapReader.require(ctx, HeapKeys.PRICING_CURRENT, PricingResult.class);
    TaxCalculation tax = HeapReader.require(ctx, HeapKeys.TAX_CALCULATED, TaxCalculation.class);

    return KubeFnResponse.ok(Map.of("total", tax.total()));
}
```

## Statelessness

Functions are stateless. Do not store request-scoped data in instance fields. All shared state goes through HeapExchange.

## Next

- [Scheduling & Queues](scheduling.md)
- [Lifecycle Hooks](lifecycle.md)
