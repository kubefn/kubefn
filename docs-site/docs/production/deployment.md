# Production Deployment

## Helm Install

```bash
helm install kubefn kubefn/kubefn \
  --namespace kubefn \
  --create-namespace \
  -f values-production.yaml
```

## Recommended values-production.yaml

```yaml
runtime:
  replicas: 2
  heap:
    maxSize: 2g
  jvm:
    opts: "-XX:+UseZGC -XX:+ZGenerational -Xms2g -Xmx2g"
  resources:
    requests:
      memory: 3Gi
      cpu: "2"
    limits:
      memory: 4Gi
      cpu: "4"

admin:
  auth:
    enabled: true
    username: admin
    password: "${ADMIN_PASSWORD}"

service:
  type: ClusterIP
  port: 8080

ingress:
  enabled: true
  className: nginx
  hosts:
    - host: kubefn.internal.example.com
      paths:
        - path: /
          pathType: Prefix
```

## JVM Tuning

Use ZGC for low-pause garbage collection. Set `-Xms` equal to `-Xmx` to avoid heap resizing.

| Heap Objects | Recommended `-Xmx` | Pod Memory Limit |
|-------------|---------------------|------------------|
| < 1000      | 512m                | 1Gi              |
| 1000-10000  | 2g                  | 4Gi              |
| 10000+      | 4g                  | 8Gi              |

Pod memory limit should be ~2x heap size to account for off-heap, classloaders, and thread stacks.

## Rolling Updates

The Helm chart uses `RollingUpdate` strategy by default. Functions are drained before shutdown via the `PRE_STOP` lifecycle phase.

```yaml
updateStrategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```

## Deploying Functions

```bash
# Via CLI
kubefn deploy build/libs/my-function.jar my-group

# Via ConfigMap (GitOps-friendly)
kubectl create configmap my-function \
  --from-file=my-function.jar=build/libs/my-function.jar \
  -n kubefn
```

## Next

- [Observability](observability.md)
- [Scaling](scaling.md)
- [Helm Values Reference](../reference/helm.md)
