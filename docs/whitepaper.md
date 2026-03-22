# Memory-Continuous Architecture: Eliminating Serialization Boundaries in Function Composition

**Pranab Sarkar**
KubeFn Project / https://pranab.co.in

**March 2026**

---

## Abstract

Modern microservice architectures impose serialization at every service boundary: objects are marshalled to JSON or Protocol Buffers, transmitted over HTTP or gRPC, and deserialized on the receiving side. In large service graphs, this serialization tax consumes 30--40% of total CPU cycles and dominates end-to-end latency. The alternative---monolithic deployment---eliminates serialization but sacrifices independent deployability, the primary operational advantage of microservices.

This paper introduces **Memory-Continuous Architecture (MCA)**, a design pattern that decouples deployment boundaries from memory boundaries. In MCA, independently deployable functions share a single runtime process heap, communicating through direct object references rather than serialized byte streams. We present the **HeapExchange**, a zero-copy shared object graph with versioned schema evolution, capacity governance, and causal audit logging. We implement MCA across three language runtimes---JVM (classloader isolation, virtual threads, Netty), CPython (shared interpreter, `importlib` hot-loading), and Node.js (V8 isolate, `require`-based module loading)---demonstrating that the pattern is language-agnostic. In full HTTP-cycle benchmarks, MCA achieves 3.8ms average latency for a 7-function JVM pipeline (4--18x improvement over equivalent microservices), 1.0ms for a 3-function Python ML inference pipeline (6--30x), and 0.3ms for a 3-function Node.js API gateway (20--100x). KubeFn, the open-source reference implementation, integrates with Kubernetes through custom resource definitions and a reconciliation-loop operator.

---

## 1. Introduction

### 1.1 The Microservices Serialization Tax

The microservices architectural style [1, 2] decomposes applications into independently deployable services communicating over network protocols. This decomposition yields significant operational benefits: independent scaling, polyglot technology choices, fault isolation, and team autonomy. However, it introduces a cost that is rarely quantified at design time.

Every inter-service call requires the caller to serialize its request (typically to JSON or Protocol Buffers), transmit the bytes over a network socket, and have the receiver deserialize them back into in-memory objects. For a single hop, this overhead is modest---2 to 10 milliseconds for a typical intra-cluster HTTP call, depending on payload size and serialization format. But microservice graphs are rarely single-hop. A user-facing request to an e-commerce checkout system might traverse authentication, inventory, pricing, shipping calculation, tax computation, fraud scoring, and order assembly services. Seven hops at 2--10ms each produces 14--70ms of latency attributable purely to serialization and network transit, before any business logic executes.

Google's internal analysis of production microservice workloads found that serialization and deserialization consumed 30--40% of total CPU cycles across some service graphs [3]. This is not an implementation deficiency; it is a structural consequence of the architectural choice to place memory boundaries at every deployment boundary.

### 1.2 The Monolith/Microservices Tradeoff

The industry has long treated this as a binary choice. Monolithic deployment keeps all code in one process, enabling direct method calls with zero serialization overhead. But monoliths couple deployment: changing one function requires redeploying the entire application. This coupling slows release velocity, increases blast radius, and prevents independent scaling.

Microservices solve the deployment problem but create the serialization problem. Various mitigation strategies exist---service meshes reduce network overhead, binary serialization formats (Protocol Buffers, FlatBuffers, Cap'n Proto) reduce marshalling cost, sidecar proxies amortize connection management---but none eliminate the fundamental issue. Data must still cross a memory boundary at every service call.

### 1.3 Why No One Has Solved This Before

Prior attempts to share runtime processes across independent components have existed for decades but have not achieved widespread adoption for function composition:

- **OSGi** [4] provides classloader-based module isolation on the JVM but imposes complex lifecycle management, bundle dependency resolution, and service registry overhead that made it notoriously difficult to operate in production.
- **Java EE application servers** (WebLogic, WebSphere, JBoss) host multiple applications in a single JVM but lack fine-grained function composition, zero-copy data sharing, and modern deployment primitives.
- **Erlang/OTP** [5] runs lightweight processes sharing a BEAM VM heap but communicates between processes via message passing with copying semantics, and the ecosystem is limited to Erlang and Elixir.

These systems either did not achieve zero-copy data sharing between independently deployable units, or they imposed operational complexity that negated the benefits, or they were confined to a single language ecosystem.

### 1.4 Contribution

This paper makes the following contributions:

1. **Memory-Continuous Architecture (MCA)**, a formally described architectural pattern in which deployment boundaries are decoupled from memory boundaries, enabling independently deployable functions to share a process heap and communicate through direct object references.

2. **The HeapExchange**, a zero-copy shared object graph with typed capsules, versioned schema evolution, capacity governance (leak detection, stale eviction), and a causal audit log suitable for production use.

3. **A multi-runtime implementation** across three major language platforms (JVM, CPython, Node.js), demonstrating that MCA is not tied to any single language's memory model or module system.

4. **Quantitative evaluation** showing 4--100x latency improvements in full HTTP-cycle benchmarks across all three runtimes, with honest methodology and conservative speedup ranges.

5. **KubeFn**, an open-source, Kubernetes-native reference implementation with CRDs, a reconciliation-loop operator, and production-grade resilience primitives.

---

## 2. Background and Related Work

### 2.1 Traditional Architectures

**Monoliths.** A monolithic application runs as a single process. Function calls are method invocations on the same heap---zero serialization, zero network transit. The cost is operational: all code deploys together, scales together, and fails together.

**Microservices.** Fowler and Lewis [1] and Newman [2] describe microservices as independently deployable services organized around business capabilities. Each service owns its data and communicates through well-defined APIs. The cost is the serialization tax described in Section 1.1, plus the operational complexity of distributed systems: service discovery, load balancing, circuit breaking, distributed tracing, and eventual consistency.

**Function-as-a-Service (FaaS).** Serverless platforms (AWS Lambda, Google Cloud Functions, Azure Functions) take microservice decomposition to its logical extreme: individual functions. Most FaaS platforms run each function invocation in an isolated container or micro-VM, imposing cold start latency (100ms--10s for JVM functions) in addition to serialization costs. Some platforms reuse warm containers, but the function-to-function communication path still traverses network boundaries.

### 2.2 Prior Shared-Runtime Approaches

**OSGi (Open Service Gateway initiative).** The OSGi specification [4] defines a module system for Java that uses classloaders to isolate bundles within a single JVM. OSGi provides a service registry through which bundles can discover and invoke each other. However, OSGi's complexity is well-documented: bundle resolution is NP-complete in the general case, the lifecycle model (installed, resolved, starting, active, stopping, uninstalled) creates intricate state machines, and the specification's treatment of class visibility across bundles produces the notorious `ClassNotFoundException` and `ClassCastException` failure modes that plagued Eclipse RCP and Apache ServiceMix deployments. Critically, OSGi was designed for modularity, not for zero-copy data sharing between independently deployable functions.

**Java EE Application Servers.** Enterprise Java application servers host multiple WAR and EAR deployments in a single JVM, sharing thread pools, connection pools, and JNDI resources. However, the communication model between deployed applications relies on remote EJB calls (network serialization), JMS messaging (serialization to byte streams), or shared databases (serialization to SQL). In-memory data sharing between deployments is neither supported nor safe under the Java EE specification.

**Erlang/OTP.** The BEAM virtual machine [5] runs millions of lightweight processes sharing a VM-level heap. Processes communicate through message passing, which in most implementations copies the message data into the receiving process's heap. While BEAM achieves remarkable fault isolation through its "let it crash" supervision model, the copy-on-send semantics mean that Erlang does not provide zero-copy data sharing across process boundaries. Furthermore, the BEAM ecosystem is limited to Erlang and Elixir.

**GraalVM and Truffle.** Oracle's GraalVM [6] supports polyglot execution---running JavaScript, Python, Ruby, and R on the JVM through the Truffle framework. GraalVM enables inter-language object sharing, but its primary design goal is polyglot interoperability rather than deployment-boundary decoupling. GraalVM native-image compilation eliminates JVM startup time but does not address the architectural question of how independently deployable units share memory.

**Project Leyden.** Project Leyden [7] aims to improve Java startup and warmup time through static images and pre-computed class initialization. While Leyden addresses the cold-start problem that plagues JVM serverless, it does not address inter-function serialization or deployment-boundary decoupling.

### 2.3 Serialization Costs in Real Systems

The cost of serialization in microservice architectures is well-documented but often underestimated:

- Google engineers reported that serialization and deserialization of Protocol Buffers consumed 30--40% of CPU cycles in some internal microservice graphs [3]. This measurement included the CPU cost of marshalling and unmarshalling but excluded the network latency of transmitting the serialized bytes.

- Benchmarks of common serialization frameworks show that even efficient binary formats require 1--10 microseconds per object for serialization and a comparable amount for deserialization [8]. For a pipeline of N functions with M shared objects, the total serialization cost scales as O(N * M).

- A 2019 study of production microservice deployments at Alibaba found that network communication (including serialization) accounted for 50--70% of end-to-end latency in request paths traversing more than three services [9].

These measurements establish a clear cost model: every memory boundary in a microservice graph imposes a latency and CPU tax proportional to the number of objects crossing that boundary. Memory-Continuous Architecture eliminates this cost for functions within the same trust boundary.

---

## 3. Memory-Continuous Architecture

### 3.1 Core Principle: Deployment Boundary != Memory Boundary

The central insight of Memory-Continuous Architecture is that the deployment boundary---the unit of independent release, scaling, and lifecycle management---need not coincide with the memory boundary---the unit of address space isolation.

In traditional microservices, these boundaries are identical: each service runs in its own process (or container), with its own heap, and all inter-service communication crosses both a deployment boundary and a memory boundary. MCA separates these concerns:

- **Deployment boundary**: each function is an independently versionable, independently loadable unit of code. Functions can be added, removed, or updated without restarting the runtime.
- **Memory boundary**: functions within a trust group share a single process heap. Data flows between functions as direct object references---same pointer, same memory address, zero copies.

This separation preserves the operational benefits of microservices (independent deployment, per-function versioning, per-function scaling via routing weights) while eliminating the serialization tax for co-located functions.

### 3.2 The HeapExchange: Zero-Copy Shared Object Graph

The HeapExchange is the data plane of MCA. It is a concurrent, typed, versioned key-value store that lives in the shared process heap. Functions publish objects to the HeapExchange; other functions retrieve them by key. The critical property: **the consumer receives the same object reference that the producer published**. There is no serialization, no deserialization, no copying.

The HeapExchange API consists of three operations:

```
publish(key, value, type) -> HeapCapsule
get(key, type) -> Optional<value>
remove(key) -> boolean
```

Each published value is wrapped in a **HeapCapsule** that records provenance metadata:

- **key**: the lookup identifier
- **value**: the published object (stored by reference)
- **type**: the runtime type for type-safe retrieval
- **version**: a monotonically increasing version counter
- **publisherGroup**: which function group published the object
- **publisherFunction**: which specific function published it
- **publishedAt**: wall-clock timestamp of publication

This metadata enables the runtime to track data lineage, detect stale objects, and enforce access patterns.

### 3.3 Capsule Design and Schema Evolution

Shared mutable state is notoriously difficult to manage. The HeapExchange mitigates this through two mechanisms:

**HeapEnvelope.** When schema evolution is required, values are wrapped in a `HeapEnvelope<T>` that carries explicit version metadata:

```
HeapEnvelope(value, schemaKey, majorVersion, minorVersion,
             publishedAt, producerRevision, metadata)
```

The compatibility rule follows semantic versioning: consumers check `isCompatibleWith(expectedMajor)`. Same major version guarantees structural compatibility; minor version differences are tolerated as additive, backward-compatible changes.

**SchemaVersion declaration.** Functions declare which versions of shared objects they produce and consume via `SchemaVersion` records:

```
SchemaVersion(key, majorVersion, minorVersion,
              producerGroup, consumerGroups)
```

The runtime can validate at load time that no consumer expects a major version that no producer provides, catching schema incompatibilities before traffic is routed to the new function.

### 3.4 HeapGuard: Capacity Governance

A shared heap without governance is a memory leak waiting to happen. The HeapGuard enforces three invariants:

1. **Capacity limits**: the HeapExchange rejects `publish` calls when the object count exceeds a configurable maximum (default: 10,000 objects). This prevents runaway functions from exhausting the shared heap.

2. **Leak detection**: the guard tracks the last access time of each key. Objects that have not been accessed within a configurable staleness threshold are candidates for eviction.

3. **Stale eviction**: a periodic eviction sweep removes objects that exceed the staleness threshold, reclaiming heap space and preventing the accumulation of orphaned state.

The guard operates with minimal overhead: publish and access tracking use `ConcurrentHashMap` operations, and the eviction sweep runs on a background timer, not on the request hot path.

### 3.5 Function Loading and Classloader Isolation

MCA requires that independently deployable functions share a heap while maintaining code isolation. The mechanism is runtime-specific:

**JVM: Classloader isolation.** Each function group is loaded by a dedicated `FunctionGroupClassLoader` that implements a child-first delegation model. Platform classes (`java.*`, `javax.*`, `com.kubefn.api.*`, `org.slf4j.*`) delegate to the parent classloader, ensuring a single copy of the API and logging facade. Function classes use child-first resolution, enabling each group to carry its own dependencies without conflicts.

Discarding a `FunctionGroupClassLoader` unloads the entire function group, making the classes eligible for garbage collection. This enables hot-swap: load the new version's classloader, drain in-flight requests to the old version, discard the old classloader.

**Python: `importlib` hot-loading.** The Python runtime uses `importlib.import_module` and `importlib.reload` to load function modules into a shared CPython interpreter. All functions share the same Python object space. The HeapExchange is a dict-based store (`dict[str, HeapCapsule]`) protected by a reentrant lock for concurrent access.

**Node.js: `require`-based module loading.** The Node.js runtime loads function modules using `require()` within a single V8 isolate. All functions share the same JavaScript heap. The HeapExchange is a `Map`-based store. Module hot-swap uses `require.cache` invalidation.

### 3.6 Hot-Swap: Replacing Functions Without Restart

Traditional microservice deployments use rolling updates: start new pods, drain old pods, terminate. This works but imposes a deployment latency of seconds to minutes. MCA enables sub-second hot-swap within a running process:

1. **Load**: the new function version is loaded into a fresh classloader (JVM), imported into the interpreter (Python), or required into the module cache (Node.js).
2. **Drain**: the `DrainManager` stops routing new requests to the old version and waits for in-flight requests to complete (with a configurable timeout).
3. **Switch**: the router atomically updates its routing table to point to the new version.
4. **Unload**: the old classloader/module is discarded, and its classes become eligible for garbage collection.

The entire cycle completes in milliseconds for the common case (no in-flight requests) or seconds under load (waiting for drain).

### 3.7 Born-Warm: New Functions Inherit Warm Runtime State

Cold start is a persistent problem in serverless and FaaS platforms, particularly for JVM-based functions where JIT compilation, class loading, and connection pool initialization can take seconds. MCA eliminates cold start for co-located functions:

- **JIT warmth**: on the JVM, the shared process has already JIT-compiled hot paths in the Netty event loop, Jackson serialization, and the HeapExchange itself. New functions benefit from these compiled code paths immediately.
- **Connection pools**: database connections, HTTP client pools, and cache clients managed by the runtime are shared. A newly loaded function does not need to establish its own connections.
- **Heap state**: the HeapExchange already contains objects published by other functions. A new function can begin consuming shared state on its first invocation.

This is the "born-warm" property: new functions are productive from their first invocation because they inherit the warm runtime state of the shared process.

### 3.8 Revision-Scoped State

When multiple revisions of a function group coexist during a canary deployment, the runtime must ensure request-level consistency. Each request is pinned to a specific revision through a `RevisionContext` that is set at dispatch time and propagated via thread-local storage:

```
RevisionContext(requestId, Map<groupName, revisionId>, createdAt)
```

The `RevisionManager` supports weighted traffic splitting: during a canary deployment, 90% of requests can be routed to the stable revision and 10% to the canary, with the weight adjustable at runtime without redeployment. Both revisions execute against the same HeapExchange, enabling zero-cost A/B comparison of function behavior.

### 3.9 The Trust Model

MCA requires a trust boundary: functions sharing a heap must trust each other not to corrupt shared state, exhaust shared resources, or behave maliciously. This is the same trust model as a monolithic application---code within the process boundary is trusted.

The trust boundary in KubeFn is the **function group**: a set of functions that are co-deployed, co-scaled, and share a classloader and HeapExchange. Functions in different groups are isolated by classloader boundaries (JVM) or separate runtime instances. Cross-group communication uses the traditional microservice path (HTTP/gRPC) with full serialization.

This is not a security weakness; it is an explicit architectural choice. MCA is appropriate for functions that would otherwise be methods in the same monolithic application. It is not appropriate for multi-tenant isolation or zero-trust environments.

---

## 4. Multi-Runtime Implementation

### 4.1 JVM Runtime

The JVM runtime is the most feature-complete implementation, leveraging the JVM's mature concurrency and class-loading infrastructure.

**Classloaders.** `FunctionGroupClassLoader` extends `URLClassLoader` with child-first delegation. Platform API classes (`com.kubefn.api.*`) are loaded from the parent to ensure type identity across function groups. Function classes are loaded from child URLs (directories or JARs containing compiled `.class` files).

**Virtual threads.** Request dispatch uses Java 21 virtual threads [10] via `Executors.newVirtualThreadPerTaskExecutor()`. User function code never executes on Netty's event loop threads, preventing blocking operations from stalling the I/O pipeline. The `FnGraphEngine` uses `StructuredTaskScope.ShutdownOnFailure` for parallel pipeline steps, ensuring clean cancellation on failure.

**Netty server.** The HTTP server is built on Netty 4.x, providing non-blocking I/O with minimal memory allocation. Request bodies are extracted from Netty's `ByteBuf` and passed to functions as byte arrays; response bodies are wrapped in `Unpooled.wrappedBuffer` to avoid copying. The Netty pipeline handles HTTP codec, request aggregation, and dispatch.

**HeapExchange.** The JVM HeapExchange uses `ConcurrentHashMap<String, HeapCapsule<?>>` as its backing store, providing O(1) lock-free reads and segment-locked writes. Version counters use `AtomicLong` for contention-free incrementing. Thread-local context (`ThreadLocal<String>`) tracks the current function's identity for publisher attribution without synchronization on the hot path.

**Pipeline composition.** The `FnGraphEngine` implements the `FnPipeline` interface, enabling declarative function composition:

```java
pipeline.step(AuthFunction.class)
        .parallel(InventoryFunction.class, PricingFunction.class)
        .step(TaxFunction.class)
        .step(AssemblyFunction.class)
        .build()
        .execute(request);
```

All steps execute as in-process method calls. Parallel steps use structured concurrency on virtual threads. The entire pipeline shares a single HeapExchange instance, enabling zero-copy data flow between steps.

### 4.2 Python Runtime

The Python runtime adapts MCA to CPython's single-interpreter model.

**Shared interpreter.** All functions run in the same CPython interpreter, sharing the same global `id()` space. When Function A publishes a NumPy array to the HeapExchange, Function B receives the same array object---same `id()`, same underlying memory buffer. For ML inference pipelines, this eliminates the cost of serializing and deserializing large tensors.

**Dict-based HeapExchange.** The Python HeapExchange uses a `dict[str, HeapCapsule]` protected by a `threading.RLock`. Read operations (`get`) do not acquire the lock in the common case (dict reads are atomic in CPython due to the GIL), reducing contention. Write operations (`publish`, `remove`) acquire the lock for compound atomic updates (version increment + store + audit).

**Module loading.** Functions are loaded as Python modules via `importlib.import_module`. Hot-swap uses `importlib.reload` after invalidating the module in `sys.modules`. The ASGI server (`uvicorn` or equivalent) continues serving requests during reload.

**GIL implications.** CPython's Global Interpreter Lock serializes Python bytecode execution. For CPU-bound functions, this limits parallelism to one core. However, for I/O-bound functions (the common case for API handlers and ML inference with C-extension backends like NumPy, PyTorch, and TensorFlow), the GIL is released during I/O and C-extension calls. The HeapExchange benefits from the GIL: dict operations are inherently thread-safe, eliminating the need for fine-grained locking.

### 4.3 Node.js Runtime

The Node.js runtime adapts MCA to V8's single-threaded event loop.

**V8 isolate.** All functions run in the same V8 isolate, sharing the same JavaScript heap. Object references are direct---`===` identity is preserved across function boundaries. The HeapExchange is a `Map` instance (O(1) lookups, insertion-order iteration).

**Module loading.** Functions are loaded via `require()`. Hot-swap invalidates `require.cache` entries and re-requires the module. The event loop continues processing requests during reload.

**Event loop implications.** Node.js is single-threaded. All function executions are interleaved on the event loop. This means the HeapExchange requires no synchronization---there are no concurrent mutations. This is both a strength (zero locking overhead) and a limitation (CPU-bound functions block the event loop). For the typical use case of API gateways, rate limiters, and request routers, the single-threaded model is ideal.

### 4.4 Common Patterns Across Runtimes

Despite the significant differences between the JVM, CPython, and V8, the MCA implementation follows a common pattern across all three:

| Concern | JVM | Python | Node.js |
|---------|-----|--------|---------|
| Isolation | Classloader | Module/namespace | `require.cache` |
| HeapExchange store | `ConcurrentHashMap` | `dict` | `Map` |
| Concurrency model | Virtual threads | GIL + async | Event loop |
| Zero-copy mechanism | Object reference | Object reference | Object reference |
| Hot-swap | Classloader discard | `importlib.reload` | Cache invalidation |
| Audit log | `HeapAuditLog` class | `list[dict]` | `Array` |

The API surface is identical: `publish(key, value, type)`, `get(key)`, `remove(key)`. The zero-copy guarantee holds uniformly: the consumer receives the same object reference the producer published.

---

## 5. System Architecture

### 5.1 Function Model

A KubeFn function is characterized by:

- **Deploy unit**: compiled `.class` files (JVM), `.py` modules (Python), or `.js` modules (Node.js). The runtime provides the framework dependencies (Jackson, Netty, Caffeine, SLF4J for JVM; aiohttp for Python; express/fastify for Node.js).
- **Routing**: each function registers one or more HTTP routes (method + path pattern). The `FunctionRouter` resolves incoming requests to function handlers with O(1) lookup for exact routes and O(N) prefix matching for wildcard routes.
- **Lifecycle**: functions implement a `KubeFnHandler` interface with a single `handle(KubeFnRequest) -> KubeFnResponse` method. The runtime manages initialization, health checking, and shutdown.

### 5.2 HeapExchange Details

The HeapExchange in the JVM implementation integrates several subsystems:

**Capsule metadata.** Every stored object is wrapped in a `HeapCapsule<T>` record containing the key, value, type, version (monotonically increasing `AtomicLong`), publisher group, publisher function, and publication timestamp. The type parameter enables type-safe retrieval: `get("pricing:result", PricingResult.class)` returns `Optional<PricingResult>` and logs a type-mismatch warning if the stored type is incompatible.

**Audit log.** The `HeapAuditLog` records every mutation (publish, access, remove) with the acting function's identity and the current revision context. This log is queryable through the admin API and feeds into the causal introspection engine.

**Capacity governance.** The `HeapGuard` enforces a maximum object count, tracks per-key access timestamps, and provides a `findStaleKeys()` method for periodic eviction. The guard's `checkPublish` method is called on every `publish` invocation and returns an error string if the operation should be blocked, keeping the guard logic out of the critical path's success case.

**Metrics.** The HeapExchange exposes publish count, get count, hit count, miss count, and hit rate as a `HeapMetrics` record. These metrics are exported to the `KubeFnMetrics` singleton for Prometheus-compatible scraping.

### 5.3 Resilience

Shared-runtime execution concentrates risk: a misbehaving function can affect all co-located functions. KubeFn addresses this through four resilience primitives:

**Circuit breakers.** Each function has a dedicated `CircuitBreaker` (Resilience4j [11]) configured with a 50% failure rate threshold, a 10-call sliding window, and a 30-second open-state duration. When a function's circuit breaker trips, the runtime returns 503 Service Unavailable and routes to a registered fallback function if one exists. Circuit breaker state transitions are logged and exposed through the admin API.

**Drain manager.** The `DrainManager` tracks in-flight request counts per function group using atomic counters. During hot-swap, the drain manager stops accepting new requests for the group being swapped and waits (with a configurable timeout) for in-flight requests to complete. This prevents request loss during deployment.

**Request timeouts.** Every function invocation is wrapped in a `CompletableFuture.get(timeout, TimeUnit.MILLISECONDS)` call. If a function exceeds its configured timeout (default: 30 seconds), the request is cancelled, the circuit breaker records a failure, and the runtime returns 504 Gateway Timeout.

**Concurrency limits.** Per-group concurrency is bounded by a `Semaphore` with a configurable maximum (default: 100 concurrent requests per group). This prevents one function group from monopolizing the virtual thread pool.

**Fallback registry.** The `FallbackRegistry` maps function identifiers to fallback handlers that execute when the primary function's circuit breaker is open or when the primary function throws an exception. Fallbacks run in the same process with access to the same HeapExchange, enabling graceful degradation (e.g., returning cached results).

### 5.4 Observability

MCA introduces a unique observability opportunity: because all function invocations occur within a single process, the runtime can observe causal relationships between function calls and heap mutations with nanosecond precision, without the coordination overhead of distributed tracing.

**Causal Capture Engine.** The `CausalCaptureEngine` sits on the hot path and captures structured events (request start/end, function start/end, heap publish/get/remove, circuit breaker trips, pipeline lifecycle, drain events) into a lock-free ring buffer (`CausalEventRing`). Events carry a monotonically increasing event ID, nanosecond timestamp, request ID, and type-specific metadata.

The ring buffer uses pre-allocated storage and atomic operations for zero-contention append. The engine can be toggled at runtime (enabled/disabled) through the admin API without restarting the process.

**Request Trace Assembly.** The `RequestTraceAssembler` reconstructs complete request traces from the event stream, correlating function invocations with heap mutations. Each `RequestTrace` includes the entry function, total duration, per-step breakdown, heap mutations caused by the request, and any errors encountered.

**Trace search.** The engine supports filtered trace search across multiple dimensions: function group, function name, minimum duration, and error presence. This enables targeted debugging ("show me all requests to the pricing function that took more than 10ms and had errors").

**OpenTelemetry integration.** The `KubeFnTracer` wraps OpenTelemetry [12] spans around every function invocation, exporting trace data to standard backends (Jaeger, Zipkin, OTLP). Each span includes the function group, function name, revision ID, request ID, and duration as attributes.

**Prometheus metrics.** The `KubeFnMetrics` singleton records invocation counts, latency histograms, circuit breaker trips, timeouts, heap publish/get operations, and hit rates. Metrics are exposed on a Prometheus-compatible `/metrics` endpoint.

### 5.5 Kubernetes Integration

KubeFn integrates with Kubernetes through two custom resource definitions and a reconciliation-loop operator:

**KubeFnGroup CRD.** Defines a function group: its name, runtime type (JVM, Python, Node.js), resource requests/limits, replica count, and configuration. The operator reconciles `KubeFnGroup` resources into `Deployment`, `Service`, and `ConfigMap` Kubernetes objects.

**KubeFnFunction CRD.** Defines an individual function within a group: its handler class, route mappings, timeout, concurrency limit, and circuit breaker configuration. The operator watches `KubeFnFunction` resources and triggers hot-swap in the running pod when a function is added, updated, or removed.

**Operator.** The KubeFn operator is built on the Java Operator SDK (JOSDK) [13] and Fabric8 Kubernetes client. It runs a standard reconciliation loop: watch CRD events, compare desired state with actual state, and apply the minimal set of Kubernetes API calls to converge.

**Helm chart.** A Helm chart packages the operator, CRDs, RBAC roles, and example function groups for single-command installation: `helm install kubefn kubefn/kubefn`.

---

## 6. Evaluation

We evaluate MCA's latency characteristics across all three runtime implementations using realistic multi-function pipelines. All benchmarks measure full HTTP request-response cycles, including network transit, request parsing, routing, function execution, heap operations, and response serialization. We compare against estimated equivalent microservice latencies based on published intra-cluster HTTP overhead measurements.

### 6.1 Methodology

**Benchmarking tool.** We use `hey` [14], a widely-used HTTP load generator. All benchmarks run 1,000 requests with 10 concurrent connections from the same machine to eliminate network variability. Results are averaged over 5 independent runs.

**Environment.** All benchmarks run on a single machine (Apple M-series, 16GB RAM) to isolate runtime performance from network topology. KubeFn runtimes run as standalone processes (not in Kubernetes) to eliminate orchestrator overhead from the measurement.

**Microservice baseline.** We estimate equivalent microservice latency as N * H, where N is the number of functions in the pipeline and H is the per-hop latency. We use two baseline ranges:
- **Intra-pod (localhost)**: 2ms per hop (optimistic: same-node, loopback interface)
- **Cross-node**: 5--10ms per hop (realistic: intra-cluster with service mesh)

These baselines are conservative; production microservice hops frequently exceed 10ms due to serialization, service mesh overhead, retries, and connection establishment.

**What we measure.** Full HTTP cycle: the `hey` client sends an HTTP request to the KubeFn runtime, which routes it through the multi-function pipeline, and returns the response. The measurement includes Netty request parsing, router resolution, function dispatch, HeapExchange operations, response serialization, and Netty response writing.

**What we do not measure.** Cold start time, JIT warmup (benchmarks run after a 100-request warmup), or Kubernetes orchestration latency. These costs are orthogonal to MCA's value proposition.

### 6.2 JVM Benchmark: Checkout Pipeline

**Setup.** A 7-function e-commerce checkout pipeline: AuthFunction (validates token), InventoryFunction (checks stock), PricingFunction (calculates price), ShippingFunction (computes shipping cost), TaxFunction (applies tax rules), FraudFunction (scores transaction risk), and AssemblyFunction (combines results into final order). Functions communicate through the HeapExchange: each function publishes its result and reads predecessors' results via direct object reference.

**Results.**

| Metric | Value |
|--------|-------|
| Average latency | 3.8 ms |
| p50 latency | 2.5 ms |
| p95 latency | 4.4 ms |
| p99 latency | 7.1 ms |
| Throughput | 2,550 req/s |

**Comparison.** Equivalent microservices: 7 hops * 2ms (optimistic) = 14ms; 7 * 10ms (cross-node) = 70ms. KubeFn achieves **4x improvement** over optimistic localhost microservices and **18x improvement** over realistic cross-node microservices. The speedup range of **4--18x** is conservative: it does not account for JSON serialization/deserialization of the shared objects at each hop, which would add 1--5ms per hop in the microservice case.

**Analysis.** The 3.8ms average includes Netty HTTP parsing (~0.2ms), virtual thread dispatch (~0.1ms), 7 function invocations with HeapExchange reads and writes (~2.5ms total), and Netty response writing (~0.2ms). The remaining ~0.8ms is attributable to Jackson serialization of the final response and overhead from the resilience stack (circuit breaker permission checks, drain manager accounting, causal event capture).

### 6.3 Python Benchmark: ML Inference Pipeline

**Setup.** A 3-function ML inference pipeline: FeatureFunction (extracts features from input), PredictFunction (runs model inference), ExplainFunction (generates prediction explanation). The feature vector, model output, and explanation are shared through the HeapExchange as Python objects (dicts and NumPy arrays).

**Results.**

| Metric | Value |
|--------|-------|
| Average latency | 1.0 ms |
| p50 latency | 0.6 ms |
| p95 latency | 0.9 ms |
| p99 latency | 1.8 ms |
| Throughput | 7,455 req/s |

**Comparison.** Equivalent microservices: 3 hops * 2ms = 6ms (optimistic); 3 * 10ms = 30ms (cross-node). KubeFn achieves **6--30x improvement**. The speedup is particularly significant for ML pipelines because the alternative involves serializing NumPy arrays or tensors at each service boundary---a cost of 1--50ms depending on array size---which MCA eliminates entirely.

**Analysis.** The GIL limits CPU-bound parallelism, but for this pipeline (I/O-bound input parsing, C-extension inference, dict-based explanation assembly), the GIL is released during the computationally intensive phases. The HeapExchange dict operations are atomic under the GIL without explicit locking.

### 6.4 Node.js Benchmark: API Gateway Pipeline

**Setup.** A 3-function API gateway: RateLimitFunction (token bucket check), AuthFunction (JWT validation), RouteFunction (upstream routing and response assembly). Functions share rate-limit state and auth context through the HeapExchange.

**Results.**

| Metric | Value |
|--------|-------|
| Average latency | 0.3 ms |
| p50 latency | 0.2 ms |
| p95 latency | 0.5 ms |
| p99 latency | 0.8 ms |
| Throughput | 33,085 req/s |

**Comparison.** Equivalent microservices: 3 hops * 2ms = 6ms (optimistic); 3 * 10ms = 30ms (cross-node). KubeFn achieves **20--100x improvement**. Node.js achieves the highest speedup ratio because its single-threaded event loop has the lowest per-invocation overhead (no thread scheduling, no lock acquisition, no context switching).

**Analysis.** The 0.3ms average includes HTTP parsing (~0.05ms), three function invocations with Map lookups (~0.15ms total), and response writing (~0.1ms). The single-threaded model means zero synchronization overhead for HeapExchange operations. The limitation is CPU-bound functions: a function that blocks the event loop for 10ms delays all other in-flight requests.

### 6.5 Threats to Validity

**Single-machine benchmarks.** All benchmarks run on a single machine, which eliminates network variability but does not capture production network conditions. The microservice baselines are estimates, not measurements of a deployed microservice system. We report speedup as a range (e.g., 4--18x) to account for this uncertainty.

**Synthetic workloads.** The benchmark functions perform lightweight computation. Production functions with heavy CPU usage (e.g., image processing, large data transformations) would show a smaller speedup ratio because the serialization tax becomes a smaller fraction of total latency.

**Warmup effects.** Benchmarks run after a warmup period. The JVM's JIT compiler has optimized hot paths before measurement begins. Cold-start performance is not measured; MCA's born-warm property addresses cold-start but is not quantified here.

---

## 7. Discussion

### 7.1 When to Use MCA vs. Microservices

MCA is not a replacement for microservices. It is a complementary pattern for a specific architectural context: multiple functions that are maintained by the same team, share trust boundaries, and compose tightly in request processing.

**Use MCA when:**
- Functions form a pipeline (A calls B calls C) and share data at each step.
- The serialization tax dominates latency (many hops, large shared objects).
- Functions are maintained by the same team and share a deployment lifecycle.
- Cold-start latency is unacceptable (e.g., JVM-based serverless).

**Use microservices when:**
- Functions are maintained by different teams with different release cadences.
- Functions require different trust boundaries or security isolation.
- Functions need independent technology stacks (different languages, databases, frameworks).
- Functions have vastly different scaling profiles (one function needs 100 replicas, another needs 2).

In practice, a large system will use both patterns: MCA for tightly-coupled function pipelines within a bounded context, and microservices for communication between bounded contexts.

### 7.2 Trust Boundaries and Security Implications

MCA functions share a process heap. A malicious or buggy function can read or corrupt any object in the HeapExchange, exhaust heap memory, or crash the process. This is the same risk profile as a monolithic application.

The mitigation is organizational: function groups should correspond to trust boundaries. Functions within a group are authored by the same team, reviewed through the same process, and deployed through the same pipeline. Cross-group communication uses standard microservice protocols with full serialization and network isolation.

For environments requiring stronger isolation (multi-tenant platforms, regulatory compliance), MCA is inappropriate. The trust model is explicit: shared heap implies shared trust.

### 7.3 Limitations

**GIL in Python.** CPython's Global Interpreter Lock prevents true parallel execution of Python bytecode. For CPU-bound function pipelines, the GIL serializes execution, limiting throughput to a single core. The mitigation is to use C-extension libraries (NumPy, PyTorch) that release the GIL during computation, or to run multiple Python runtime processes with a load balancer.

**Single-threaded Node.js.** V8's single-threaded event loop means all function invocations are sequential. A function that blocks the event loop (synchronous computation, synchronous I/O) delays all other in-flight requests. The mitigation is to offload CPU-bound work to worker threads (which cross a memory boundary, losing the zero-copy property for offloaded data).

**HeapExchange is not a database.** The HeapExchange is an in-memory, per-process data structure. It does not provide persistence, replication, transactions, or cross-process sharing. Functions that require durable state must use external databases. The HeapExchange is best suited for ephemeral, request-scoped, or session-scoped shared state.

**Schema evolution at scale.** While HeapEnvelope and SchemaVersion provide basic schema compatibility checking, they do not support automated schema migration, schema registries, or compile-time compatibility validation. In large deployments with many function groups producing and consuming shared objects, schema management becomes a coordination challenge.

### 7.4 Comparison to GraalVM Native Image and Project Leyden

GraalVM native-image [6] compiles Java applications ahead-of-time into native executables, eliminating JVM startup time and reducing memory footprint. Project Leyden [7] takes a similar approach within the OpenJDK ecosystem. Both address the cold-start problem but do not address the serialization-boundary problem.

MCA and native-image/Leyden are complementary: a KubeFn runtime compiled with GraalVM native-image would combine sub-100ms startup with zero-copy function composition. The main obstacle is that native-image's closed-world assumption conflicts with MCA's dynamic classloader-based function loading. A native-image-compatible MCA implementation would require ahead-of-time registration of all function classes, sacrificing runtime hot-swap.

### 7.5 Future Work

**Cross-runtime HeapExchange.** The current implementation requires all functions in a group to use the same language runtime. A cross-runtime HeapExchange would enable JVM functions and Python functions to share objects. The challenge is representation: a Java `HashMap` and a Python `dict` have different memory layouts. Potential approaches include shared-memory regions with a common binary format (similar to Apache Arrow's columnar format), or a polyglot runtime like GraalVM Truffle.

**Deterministic replay.** The CausalCaptureEngine records all function invocations and heap mutations with nanosecond precision. A natural extension is deterministic replay: given a captured trace, re-execute the exact sequence of function calls with the exact heap state. This would enable time-travel debugging for production incidents.

**Autonomous optimization.** The runtime has complete visibility into function call graphs, heap access patterns, and latency profiles. Future versions could automatically fuse frequently co-invoked functions (eliminating dispatch overhead), memoize pure-function subgraphs (eliminating redundant computation), and pre-warm heap state based on predicted request patterns.

**Cross-process HeapExchange.** For function groups that exceed single-process capacity, a shared-memory HeapExchange (using `MappedByteBuffer` on the JVM or `mmap` on POSIX systems) could extend the zero-copy property across processes on the same node, with a binary serialization fallback for cross-node communication.

---

## 8. Conclusion

Memory-Continuous Architecture addresses a structural inefficiency in microservice systems: the equation of deployment boundaries with memory boundaries. By separating these concerns, MCA enables independently deployable functions to share a process heap, communicating through direct object references rather than serialized byte streams.

The HeapExchange provides a typed, versioned, governed zero-copy data plane with capacity limits, leak detection, stale eviction, and causal audit logging. The multi-runtime implementation across JVM, CPython, and Node.js demonstrates that MCA is a language-agnostic architectural pattern, not a JVM-specific optimization.

Benchmarks show 4--18x latency improvement for JVM pipelines, 6--30x for Python ML inference, and 20--100x for Node.js API gateways, measured as full HTTP request-response cycles. These improvements come from eliminating serialization, deserialization, and network transit at every function boundary.

MCA is not a replacement for microservices. It is a third option between monoliths and microservices, applicable when functions share trust boundaries and compose tightly in request processing. The trust model is explicit: shared heap implies shared trust, and cross-boundary communication continues to use standard microservice protocols.

KubeFn, the open-source reference implementation, is available at https://kubefn.com and https://github.com/kubefn/kubefn. It integrates with Kubernetes through custom resource definitions, supports hot-swap deployment without restart, and includes production-grade resilience primitives (circuit breakers, drain management, timeouts, fallbacks) and observability (causal introspection, OpenTelemetry tracing, Prometheus metrics).

---

## References

[1] J. Lewis and M. Fowler, "Microservices: A definition of this new architectural term," martinfowler.com, Mar. 2014. [Online]. Available: https://martinfowler.com/articles/microservices.html

[2] S. Newman, *Building Microservices: Designing Fine-Grained Systems*, 2nd ed. O'Reilly Media, 2021.

[3] S. Kanev, J. P. Darago, K. Hazelwood, P. Ranganathan, B. Moseley, G.-Y. Wei, and D. Brooks, "Profiling a warehouse-scale computer," in *Proc. ACM/IEEE 42nd International Symposium on Computer Architecture (ISCA)*, 2015, pp. 158--169. doi: 10.1145/2749469.2750392

[4] OSGi Alliance, "OSGi Core Release 8 Specification," 2020. [Online]. Available: https://docs.osgi.org/specification/

[5] J. Armstrong, *Programming Erlang: Software for a Concurrent World*, 2nd ed. Pragmatic Bookshelf, 2013.

[6] T. Wuerthinger et al., "GraalVM: Run Programs Faster Anywhere," Oracle, 2019. [Online]. Available: https://www.graalvm.org/

[7] M. Reinhold, "Project Leyden: Beginnings," OpenJDK, May 2022. [Online]. Available: https://openjdk.org/projects/leyden/notes/01-beginnings

[8] A. Sumaray and S. K. Makki, "A comparison of data serialization formats for optimal efficiency and interoperability of programmatic interfaces," in *Proc. International Conference on Information and Knowledge Engineering (IKE)*, 2012, pp. 243--249.

[9] X. Zhou, X. Peng, T. Xie, J. Sun, C. Ji, D. Liu, Q. Xiang, and C. He, "Latency-based SLA-aware autoscaling for cloud microservices," in *Proc. IEEE International Conference on Cloud Computing (CLOUD)*, 2019, pp. 35--42.

[10] A. Bateman, R. Pressler, et al., "JEP 444: Virtual Threads," OpenJDK, 2023. [Online]. Available: https://openjdk.org/jeps/444

[11] R. Vos, "Resilience4j: Fault tolerance library designed for Java 8 and functional programming," 2023. [Online]. Available: https://resilience4j.readme.io/

[12] OpenTelemetry Authors, "OpenTelemetry Specification," Cloud Native Computing Foundation, 2024. [Online]. Available: https://opentelemetry.io/docs/specs/otel/

[13] Container Solutions, "Java Operator SDK," 2024. [Online]. Available: https://javaoperatorsdk.io/

[14] R. Saito, "hey: HTTP load generator," 2023. [Online]. Available: https://github.com/rakyll/hey
