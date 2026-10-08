FROM python:3.12-slim
RUN useradd --uid 10001 --create-home zyrex && mkdir -p /var/lib/zyrex-explorer \
    && chown zyrex:zyrex /var/lib/zyrex-explorer
WORKDIR /opt/zyrex-explorer
COPY explorer/server.py explorer/index.html ./
COPY pool/pow.py ./
USER zyrex
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
ENTRYPOINT ["python3", "server.py", "--config", "/etc/zyrex-explorer/config.json"]
