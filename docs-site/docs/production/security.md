# Security

## Trust Model

KubeFn runs multiple functions in a single JVM. **All functions in an organism have full access to the shared heap.** This means:

- Functions can read any heap key (not just their own)
- Functions share the same process and memory space
- A malicious function could read or corrupt any heap entry

**KubeFn is designed for same-team, trusted code.** Do not deploy untrusted or third-party functions into the same organism.

## Admin Auth

Enable basic auth for admin endpoints in production:

```yaml
admin:
  auth:
    enabled: true
    username: admin
    password: "${ADMIN_PASSWORD}"
```

Admin endpoints (`/admin/*`) will require authentication. Function endpoints remain unauthenticated by default -- use your own auth function or an ingress-level solution.

## Container Hardening

The Helm chart defaults:

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  readOnlyRootFilesystem: true
  allowPrivilegeEscalation: false
  capabilities:
    drop: [ALL]
```

## Network Policies

Restrict traffic to the organism:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: kubefn-policy
  namespace: kubefn
spec:
  podSelector:
    matchLabels:
      app: kubefn-runtime
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: my-app
      ports:
        - port: 8080
  egress:
    - to:
        - namespaceSelector: {}
      ports:
        - port: 5432  # postgres
        - port: 6379  # redis
```

## What KubeFn Does NOT Protect Against

- Functions reading other functions' heap entries
- Functions mutating shared heap objects (violating immutability)
- Resource exhaustion by a single function (heap, threads, CPU)
- Classloader escapes between function groups

These are acceptable tradeoffs for same-team code. If you need process-level isolation, use separate organisms or separate pods.

## Next

- [Scaling](scaling.md)
- [Should I Use KubeFn?](../migration/should-i-use.md)
