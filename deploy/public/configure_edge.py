#!/usr/bin/env python3
"""Install Zyrex edge routes while retaining the existing TLS proxy on port 443."""
import argparse
import hashlib
import os
import re
import subprocess
import tempfile
from pathlib import Path


def atomic(path, data):
    handle, name = tempfile.mkstemp(dir=path.parent, prefix='.zyrex-')
    try:
        with os.fdopen(handle, 'wb') as stream:
            os.fchmod(stream.fileno(), 0o644)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--domain', required=True)
    args = parser.parse_args()
    domain = args.domain
    if not re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)+', domain):
        raise ValueError('Invalid domain')
    nginx = Path('/etc/nginx/nginx.conf')
    site = Path('/etc/nginx/sites-available/zyrexchain.com')
    stream = Path('/etc/nginx/zyrex-stream.conf')
    old = {p: p.read_bytes() if p.exists() else None for p in (nginx, site, stream)}
    main_config = old[nginx].decode()
    marker = '    include /etc/nginx/zyrex-stream.conf;'
    if marker not in main_config:
        if main_config.count('    stream {') or main_config.count('\nstream {') != 1:
            raise RuntimeError('Expected exactly one existing top-level stream section')
        if main_config.count('        listen 443 ssl;') != 1:
            raise RuntimeError('Expected the existing TLS stream listener on port 443')
        main_config = main_config.replace('        listen 443 ssl;', '        listen 127.0.0.1:12443 ssl;', 1)
        main_config = main_config.replace('\nstream {', '\nstream {\n' + marker, 1)
    certificate = Path('/etc/letsencrypt/live') / domain
    for name in ('fullchain.pem', 'privkey.pem'):
        if not (certificate / name).is_file():
            raise RuntimeError('Certificate has not been issued')
    names = f'{domain} explorer.{domain} stratum.{domain}'
    http_config = f'''map $host $zyrex_web_backend {{
    default 127.0.0.1:28088;
    explorer.{domain} 127.0.0.1:28080;
}}
server {{
    listen 80;
    listen [::]:80;
    server_name {names};
    location ^~ /.well-known/acme-challenge/ {{
        root /var/www/zyrex-acme;
        default_type text/plain;
        try_files $uri =404;
    }}
    location / {{ return 301 https://$host$request_uri; }}
}}
server {{
    listen 127.0.0.1:28443 ssl;
    server_name {names};
    ssl_certificate {certificate}/fullchain.pem;
    ssl_certificate_key {certificate}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    server_tokens off;
    client_max_body_size 16k;
    location / {{
        limit_except GET HEAD {{ deny all; }}
        proxy_pass http://$zyrex_web_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_connect_timeout 3s;
        proxy_read_timeout 30s;
        proxy_intercept_errors on;
        error_page 502 504 =503 @starting;
    }}
    location @starting {{
        default_type text/plain;
        return 503 "Zyrex public testnet services are starting.\\n";
    }}
}}
'''
    stream_config = f'''map $ssl_preread_server_name $zyrex_tls_backend {{
    {domain} 127.0.0.1:28443;
    explorer.{domain} 127.0.0.1:28443;
    stratum.{domain} 127.0.0.1:28443;
    default 127.0.0.1:12443;
}}
server {{
    listen 443;
    ssl_preread on;
    proxy_pass $zyrex_tls_backend;
    proxy_connect_timeout 5s;
    proxy_timeout 3600s;
}}
server {{
    listen 3333;
    proxy_pass 127.0.0.1:23333;
    proxy_connect_timeout 5s;
    proxy_timeout 3600s;
}}
server {{
    listen 3443 ssl;
    ssl_certificate {certificate}/fullchain.pem;
    ssl_certificate_key {certificate}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    proxy_pass 127.0.0.1:23333;
    proxy_connect_timeout 5s;
    proxy_timeout 3600s;
}}
server {{
    listen 19533;
    proxy_pass 127.0.0.1:29533;
    proxy_connect_timeout 5s;
    proxy_timeout 3600s;
}}
'''
    backup = Path('/etc/nginx/zyrex-backup')
    backup.mkdir(mode=0o700, exist_ok=True)
    first_backup = backup / 'nginx-before-zyrex.conf'
    if not first_backup.exists():
        first_backup.write_bytes(old[nginx])
        first_backup.chmod(0o600)
    try:
        atomic(stream, stream_config.encode())
        atomic(site, http_config.encode())
        atomic(nginx, main_config.encode())
        subprocess.run(['nginx', '-t'], check=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
    except Exception:
        for path, data in old.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                atomic(path, data)
        subprocess.run(['nginx', '-t'], check=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
        raise
    for path in (nginx, site, stream):
        print(path.name, hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
