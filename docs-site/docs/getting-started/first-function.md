# Your First Function

## The KubeFnHandler interface

Every function implements one method:

```java
public interface KubeFnHandler {
    KubeFnResponse handle(KubeFnRequest request) throws Exception;
}
```

## Annotations

`@FnRoute` maps HTTP paths. `@FnGroup` assigns classloader isolation:

```java
@FnRoute(path = "/api/pricing", methods = {"POST"})
@FnGroup("checkout")
public class PricingFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // parse request
        var body = request.bodyAs(PricingRequest.class);

        // compute pricing
        double discount = body.quantity() > 10 ? 0.15 : 0.0;
        double finalPrice = body.unitPrice() * body.quantity() * (1 - discount);

        // publish to heap for downstream functions
        var result = new PricingResult("USD", body.unitPrice(), discount, finalPrice);
        ctx.heap().publish(HeapKeys.PRICING_CURRENT, result, PricingResult.class);

        return KubeFnResponse.ok(result);
    }
}
```

## Reading from HeapExchange

Use typed `HeapKeys` constants. Always handle the absent case:

```java
PricingResult pricing = ctx.heap()
    .get(HeapKeys.PRICING_CURRENT, PricingResult.class)
    .orElseThrow(() -> new IllegalStateException("Pricing not on heap"));
```

Or with a fallback:

```java
PricingResult pricing = HeapReader.getOrDefault(
    ctx, HeapKeys.PRICING_CURRENT, PricingResult.class,
    () -> new PricingResult("USD", 0, 0, 0));
```

## Publishing to HeapExchange

```java
var tax = new TaxCalculation(subtotal, 0.0825, taxAmount, total);
ctx.heap().publish(HeapKeys.TAX_CALCULATED, tax, TaxCalculation.class);
```

## Testing with FakeHeapExchange

```java
@Test
void testPricingPublishesToHeap() {
    var heap = new FakeHeapExchange();
    var ctx = FnContext.withHeap(heap);
    var fn = new PricingFunction();
    fn.setContext(ctx);

    var request = KubeFnRequest.builder()
        .body(new PricingRequest("SKU-1", 29.99, 5))
        .build();

    var response = fn.handle(request);

    assertEquals(200, response.status());
    assertTrue(heap.contains(HeapKeys.PRICING_CURRENT));

    PricingResult result = heap.get(HeapKeys.PRICING_CURRENT, PricingResult.class).get();
    assertEquals(149.95, result.finalPrice(), 0.01);
}
```

## Next

- [Architecture](../concepts/architecture.md) -- understand the organism model
- [HeapExchange deep dive](../concepts/heap-exchange.md)
