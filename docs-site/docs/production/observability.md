# Observability

## Prometheus Metrics

The runtime exposes metrics at `/admin/prometheus` in OpenMetrics format.

```bash
curl http://localhost:8080/admin/prometheus
```

Key metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `kubefn_request_duration_seconds` | histogram | Per-function request latency |
| `kubefn_heap_entries_total` | gauge | Number of objects on the heap |
| `kubefn_heap_size_bytes` | gauge | Estimated heap usage |
| `kubefn_function_invocations_total` | counter | Invocation count per function |
| `kubefn_function_errors_total` | counter | Error count per function |

Scrape config for Prometheus:

```yaml
- job_name: kubefn
  kubernetes_sd_configs:
    - role: pod
      namespaces:
        names: [kubefn]
  relabel_configs:
    - source_labels: [__meta_kubernetes_pod_label_app]
      regex: kubefn-runtime
      action: keep
  metrics_path: /admin/prometheus
```

## Grafana Dashboard

Import the pre-built dashboard:

```bash
kubefn grafana-dashboard > kubefn-dashboard.json
```

Panels: request rate, p50/p95/p99 latency, heap entries, error rate, JVM memory, GC pauses.

## Causal Introspection

View function call chains and heap interactions:

```bash
curl http://localhost:8080/admin/traces
```

Returns a timeline of function invocations, heap publishes, and heap reads for the last N requests. Useful for debugging pipeline ordering issues.

## Trace UI

```bash
curl http://localhost:8080/admin/ui
```

A built-in web UI showing:

- Live function invocation timeline
- Heap state viewer (current keys, values, publishers)
- Function dependency graph (who reads/writes what)

## Per-Function Stats

```bash
curl http://localhost:8080/admin/functions
```

Returns per-function invocation count, average latency, error rate, and last invocation time.

## Next

- [Security](security.md)
- [Prometheus Metrics Reference](../reference/metrics.md)
