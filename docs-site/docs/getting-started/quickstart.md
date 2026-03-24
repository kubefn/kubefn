# Quickstart (5 minutes)

## Prerequisites

- Java 21+
- Docker (for `kubefn dev`)

## Step 1: Scaffold a project

```bash
kubefn init my-service my-group
cd my-service
```

This generates a Gradle project with `kubefn-api` and `kubefn-contracts` already on the classpath.

## Step 2: Explore the project

```
my-service/
  src/main/java/
    MyFunction.java          # Your function
  contracts/
    HeapKeys.java            # Typed heap key constants
  build.gradle               # kubefn-api, kubefn-contracts as compileOnly
  kubefn.yml                 # Runtime config
```

Open `HeapKeys.java` -- this is where you define typed keys for heap objects.

## Step 3: Run tests

```bash
./gradlew test
```

The generated tests use `FakeHeapExchange` -- a local in-memory implementation for unit testing. No runtime needed.

## Step 4: Write a function

```java
@FnRoute(path = "/greet", methods = {"GET"})
@FnGroup("my-group")
public class GreetFunction implements KubeFnHandler {
    @Override
    public KubeFnResponse handle(KubeFnRequest request) {
        return KubeFnResponse.ok(Map.of("message", "hello from KubeFn"));
    }
}
```

## Step 5: Run locally

```bash
kubefn dev
```

```bash
curl http://localhost:8080/greet
# {"message": "hello from KubeFn"}
```

## The "aha moment"

Deploy two functions. Function A publishes a `PricingResult` to the heap. Function B reads it -- same object, same reference, zero serialization:

```java
// Function A
ctx.heap().publish(HeapKeys.PRICING_CURRENT, pricingResult, PricingResult.class);

// Function B (different JAR, same JVM)
PricingResult pricing = ctx.heap().get(HeapKeys.PRICING_CURRENT, PricingResult.class).get();
// pricing == the SAME object. No copy. No serialization.
```

## Next

- [Deploy to Kubernetes](kubernetes.md)
- [Your First Function (detailed)](first-function.md)
