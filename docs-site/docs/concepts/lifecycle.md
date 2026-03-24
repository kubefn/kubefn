# Lifecycle Hooks

KubeFn functions can hook into the organism lifecycle, replacing init containers and shutdown scripts.

## @FnLifecyclePhase

```java
@FnLifecyclePhase(phase = LifecyclePhase.INIT, priority = 10)
@FnGroup("bootstrap")
public class DatabaseMigrator implements KubeFnHandler {
    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // Run migrations before any function handles traffic
        Flyway.configure().dataSource(dbUrl, user, pass).load().migrate();
        return KubeFnResponse.ok("migrations complete");
    }
}
```

## Phases

| Phase | When | Use Case |
|-------|------|----------|
| `INIT` | Before the server accepts traffic | DB migrations, cache warming, config loading |
| `READY` | After all INIT hooks complete | Health check registration, announce readiness |
| `PRE_STOP` | When shutdown signal received | Drain connections, flush buffers |
| `SHUTDOWN` | After PRE_STOP, before JVM exits | Close connections, cleanup resources |

## Ordering

Functions in the same phase execute in `priority` order (lowest first):

```java
@FnLifecyclePhase(phase = LifecyclePhase.INIT, priority = 1)   // runs first
public class ConfigLoader implements KubeFnHandler { ... }

@FnLifecyclePhase(phase = LifecyclePhase.INIT, priority = 10)  // runs second
public class DatabaseMigrator implements KubeFnHandler { ... }

@FnLifecyclePhase(phase = LifecyclePhase.INIT, priority = 20)  // runs third
public class CacheWarmer implements KubeFnHandler { ... }
```

## Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `phase` | `LifecyclePhase` | required | Which phase to run in |
| `priority` | `int` | `100` | Execution order (lower = earlier) |
| `timeout` | `String` | `"30s"` | Max execution time |
| `failFast` | `boolean` | `true` | If true, failure aborts startup |

## Replaces Init Containers

Instead of:

```yaml
initContainers:
  - name: migrate-db
    image: my-app:latest
    command: ["flyway", "migrate"]
```

Write a lifecycle function. It runs in the same JVM, has access to the heap, and can publish results for other functions to use during INIT.

## Admin Endpoint

```bash
curl http://localhost:8080/admin/lifecycle
# {"init": ["ConfigLoader(1)", "DatabaseMigrator(10)"], "ready": [...]}
```

## Next

- [Deployment](../production/deployment.md)
- [Architecture](architecture.md)
