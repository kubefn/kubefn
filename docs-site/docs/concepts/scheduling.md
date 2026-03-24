# Scheduling & Queues

KubeFn replaces Kubernetes CronJobs and queue worker Deployments with annotations.

## @FnSchedule

Run functions on a cron schedule:

```java
@FnSchedule(cron = "0 */5 * * * *")  // every 5 minutes
@FnGroup("background")
public class CacheWarmer implements KubeFnHandler {
    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // warm the cache, publish to heap
        ctx.heap().publish(HeapKeys.PRODUCT_CACHE, products, ProductCache.class);
        return KubeFnResponse.ok("cache warmed");
    }
}
```

### Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `cron` | `String` | required | Cron expression (6-field, with seconds) |
| `skipIfRunning` | `boolean` | `true` | Skip if previous invocation still running |
| `timeout` | `String` | `"5m"` | Max execution time |
| `runOnStart` | `boolean` | `false` | Run immediately on deploy |

### Cron format

```
┌──────── second (0-59)
│ ┌────── minute (0-59)
│ │ ┌──── hour (0-23)
│ │ │ ┌── day of month (1-31)
│ │ │ │ ┌ month (1-12)
│ │ │ │ │ ┌ day of week (0-6, 0=Sun)
* * * * * *
```

## @FnQueue

Process messages from a queue:

```java
@FnQueue(name = "order-events", concurrency = 4)
@FnGroup("order-processing")
public class OrderProcessor implements KubeFnHandler {
    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var event = request.bodyAs(OrderEvent.class);
        // process event
        return KubeFnResponse.ok("processed");
    }
}
```

### Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | `String` | required | Queue name |
| `concurrency` | `int` | `1` | Parallel consumers |
| `batchSize` | `int` | `1` | Messages per invocation |
| `visibilityTimeout` | `String` | `"30s"` | Redelivery timeout |

## Advantages Over K8s CronJobs

- No pod startup latency (function is already loaded)
- Shared heap state (cron function can read/write same data)
- Managed via `/admin/scheduler` endpoint
- No YAML proliferation

## Next

- [Lifecycle Hooks](lifecycle.md)
- [Cron Replacement Example](../examples/schedulers.md)
