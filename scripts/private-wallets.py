#!/usr/bin/env python3
"""Initialize/unlock private wallets, retaining generated credentials only in .local/."""
import json
import secrets
import urllib.request
from pathlib import Path


def api(port, path, payload=None):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}",
                                 data=None if payload is None else json.dumps(payload).encode(),
                                 headers={"api_key": "hello", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as response:
        data = response.read().decode()
        return json.loads(data) if data else None


def main():
    folder = Path(".local/wallets")
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    for node, port in enumerate(range(19555, 19558), start=1):
        path = folder / f"node{node}.json"
        status = api(port, "/wallet/status")
        if not status["isInitialized"]:
            if path.exists():
                saved = json.loads(path.read_text())
                api(port, "/wallet/restore", {"pass": saved["password"], "mnemonic": saved["mnemonic"],
                                              "usePre1627KeyDerivation": False})
            else:
                password = secrets.token_urlsafe(32)
                initialized = api(port, "/wallet/init", {"pass": password})
                path.touch(mode=0o600, exist_ok=False)
                path.write_text(json.dumps({"password": password, "mnemonic": initialized["mnemonic"]}))
        if not path.exists():
            raise RuntimeError(f"node{node}: wallet already initialized outside this helper; unlock it with the CLI")
        saved = json.loads(path.read_text())
        if not api(port, "/wallet/status")["isUnlocked"]:
            api(port, "/wallet/unlock", {"pass": saved["password"]})
        print(f"node{node}: wallet unlocked; credentials saved in {path}")


if __name__ == "__main__":
    main()
