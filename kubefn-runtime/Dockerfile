FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Pranab Sarkar <mail@pranab.co.in>"
LABEL org.opencontainers.image.title="KubeFn Runtime"
LABEL org.opencontainers.image.description="Live Application Fabric — Memory-Continuous Architecture"

# Create non-root user
RUN addgroup -S kubefn && adduser -S kubefn -G kubefn

# Create functions directory
RUN mkdir -p /var/kubefn/functions && chown -R kubefn:kubefn /var/kubefn

# Copy runtime fat JAR
COPY build/libs/kubefn-runtime-*-all.jar /opt/kubefn/runtime.jar

# Switch to non-root
USER kubefn

# Expose ports
EXPOSE 8080 8081

# Health check
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O- http://localhost:8081/healthz || exit 1

ENTRYPOINT ["java", \
    "--enable-preview", \
    "-XX:+UseZGC", \
    "-XX:+DisableAttachMechanism", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/opt/kubefn/runtime.jar"]
