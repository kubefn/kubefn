# Kubernetes Deploy

Get KubeFn running in your cluster in 10 minutes.

## Prerequisites

- `kubectl` configured for your cluster
- Helm 3+

## Step 1: Install the runtime

```bash
helm repo add kubefn https://charts.kubefn.com
helm repo update

helm install kubefn kubefn/kubefn \
  --namespace kubefn \
  --create-namespace \
  --set runtime.heap.maxSize=512m
```

## Step 2: Verify

```bash
kubectl get pods -n kubefn
```

```
NAME                      READY   STATUS    RESTARTS   AGE
kubefn-runtime-0          1/1     Running   0          30s
```

```bash
kubectl logs kubefn-runtime-0 -n kubefn
# KubeFn runtime started on :8080 (heap: 512m, functions: 0)
```

## Step 3: Port-forward and test

```bash
kubectl port-forward svc/kubefn-runtime 8080:8080 -n kubefn
```

```bash
curl http://localhost:8080/admin/health
# {"status": "UP"}

curl http://localhost:8080/admin/functions
# []
```

## Step 4: Deploy a function

Build your function JAR (from the quickstart):

```bash
./gradlew build
kubefn deploy build/libs/my-service.jar my-group
```

Or with kubectl directly:

```bash
kubectl cp build/libs/my-service.jar kubefn/kubefn-runtime-0:/functions/
curl -X POST http://localhost:8080/admin/reload
```

## Step 5: Verify deployment

```bash
curl http://localhost:8080/admin/functions
# [{"name": "GreetFunction", "group": "my-group", "route": "/greet"}]

curl http://localhost:8080/greet
# {"message": "hello from KubeFn"}
```

## Next

- [Your First Function](first-function.md) -- detailed walkthrough
- [Production Deployment](../production/deployment.md) -- Helm values, resource sizing, JVM tuning
