#!/bin/bash
set -euo pipefail

# KubeFn Deploy Script
# Builds, pushes to Harbor, and deploys to K8s cluster

REGISTRY="harbor.mycluster.cyou"
IMAGE="${REGISTRY}/kubefn/runtime:latest"
NAMESPACE="kubefn"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== KubeFn Deploy ==="
echo "Registry: ${REGISTRY}"
echo "Image: ${IMAGE}"
echo "Namespace: ${NAMESPACE}"

# Step 1: Build the project
echo ""
echo ">>> Building project..."
cd "${PROJECT_ROOT}"
./gradlew :kubefn-runtime:shadowJar :examples:hello-function:jar

# Step 2: Build Docker image with function baked in for demo
echo ""
echo ">>> Building Docker image..."
cat > "${PROJECT_ROOT}/Dockerfile.demo" <<'DOCKERFILE'
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Pranab Sarkar <mail@pranab.co.in>"
LABEL org.opencontainers.image.title="KubeFn Runtime"
LABEL org.opencontainers.image.description="Live Application Fabric — Memory-Continuous Architecture"

RUN addgroup -S kubefn && adduser -S kubefn -G kubefn
RUN mkdir -p /var/kubefn/functions/hello-service && chown -R kubefn:kubefn /var/kubefn

COPY kubefn-runtime/build/libs/kubefn-runtime-*-all.jar /opt/kubefn/runtime.jar
COPY examples/hello-function/build/libs/hello-function-*.jar /var/kubefn/functions/hello-service/

USER kubefn
EXPOSE 8080 8081

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O- http://localhost:8081/healthz || exit 1

ENTRYPOINT ["java", \
    "--enable-preview", \
    "-XX:+UseZGC", \
    "-XX:+DisableAttachMechanism", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/opt/kubefn/runtime.jar"]
DOCKERFILE

docker build -t "${IMAGE}" -f Dockerfile.demo .

# Step 3: Push to Harbor
echo ""
echo ">>> Pushing to Harbor..."
docker push "${IMAGE}"

# Step 4: Apply CRDs
echo ""
echo ">>> Applying CRDs..."
kubectl apply -f deploy/crds/

# Step 5: Create namespace
echo ""
echo ">>> Creating namespace..."
kubectl apply -f deploy/manifests/namespace.yaml

# Step 6: Deploy runtime
echo ""
echo ">>> Deploying runtime..."
kubectl apply -f deploy/manifests/runtime-deployment.yaml

# Step 7: Wait for rollout
echo ""
echo ">>> Waiting for rollout..."
kubectl rollout status deployment/kubefn-runtime -n "${NAMESPACE}" --timeout=120s

# Step 8: Apply example function CRDs
echo ""
echo ">>> Applying example function CRDs..."
kubectl apply -f deploy/manifests/example-function.yaml

# Step 9: Show status
echo ""
echo "=== Deployment Complete ==="
kubectl get pods -n "${NAMESPACE}"
echo ""
kubectl get svc -n "${NAMESPACE}"
echo ""
kubectl get kfg -n "${NAMESPACE}" 2>/dev/null || true
echo ""
kubectl get kff -n "${NAMESPACE}" 2>/dev/null || true

echo ""
echo ">>> Test with:"
echo "    kubectl port-forward -n ${NAMESPACE} svc/kubefn-runtime 8080:80 8081:8081 &"
echo '    curl http://localhost:8080/greet?name=KubeFn'
echo '    curl http://localhost:8080/echo'
echo '    curl http://localhost:8081/admin/functions'
