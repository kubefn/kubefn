FROM python:3.12-slim

LABEL maintainer="Pranab Sarkar <developer@pranab.co.in>"
LABEL org.opencontainers.image.title="KubeFn Python Runtime"
LABEL org.opencontainers.image.description="Memory-Continuous Architecture for Python"

RUN addgroup --system kubefn && adduser --system --group kubefn

WORKDIR /opt/kubefn

COPY kubefn/ ./kubefn/
COPY pyproject.toml .

RUN pip install --no-cache-dir -e .

RUN mkdir -p /var/kubefn/functions && chown -R kubefn:kubefn /var/kubefn

USER kubefn

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=3s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8080/healthz')" || exit 1

ENTRYPOINT ["python", "-m", "kubefn"]
