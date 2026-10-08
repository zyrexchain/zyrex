FROM python:3.12-slim
RUN useradd --uid 10001 --create-home zyrex && mkdir -p /var/lib/zyrex-pool \
    && chown zyrex:zyrex /var/lib/zyrex-pool && chmod 770 /var/lib/zyrex-pool
WORKDIR /opt/zyrex-pool
COPY pow.py ledger.py node.py server.py index.html ./
USER zyrex
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
EXPOSE 3333 8088
ENTRYPOINT ["python3", "server.py", "--config", "/etc/zyrex-pool/config.json"]
