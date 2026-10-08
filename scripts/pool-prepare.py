#!/usr/bin/env python3
"""Prepare a dedicated LAN pool node; all credentials stay in ignored .local/pool."""
import argparse
import hashlib
import json
import os
import secrets
import time
import urllib.request
from pathlib import Path

FOLDER = Path(".local/pool")


def save(path, text, public=False):
    temporary = path.with_suffix(path.suffix + ".tmp")
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as stream:
        stream.write(text)
        stream.flush()
        os.fsync(stream.fileno())
        os.fchmod(stream.fileno(), 0o644 if public else 0o600)
    if os.getuid() == 0:
        os.chown(temporary, 10001, 10001)
    temporary.replace(path)


def api(path, payload=None):
    req = urllib.request.Request("http://127.0.0.1:19558" + path,
        data=None if payload is None else json.dumps(payload).encode(),
        headers={"api_key": (FOLDER / "api-key").read_text().strip(), "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as response:
        body = response.read()
        return json.loads(body) if body else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["config", "wallet"])
    args = parser.parse_args()
    FOLDER.mkdir(parents=True, mode=0o700, exist_ok=True)
    if os.getuid() == 0:
        os.chown(FOLDER, 10001, 10001)
    if args.action == "config":
        keyfile = FOLDER / "api-key"
        if not keyfile.exists():
            save(keyfile, secrets.token_urlsafe(32))
        key_hash = hashlib.blake2b(keyfile.read_text().strip().encode(), digest_size=32).hexdigest()
        walletfile = FOLDER / "wallet.json"
        wallet = json.loads(walletfile.read_text()) if walletfile.exists() else {}
        mining_key = wallet.get("miningPublicKey")
        mining_setting = [f'zyrex.node.miningPubKeyHex = "{mining_key}"'] if mining_key else []
        save(FOLDER / "node.conf", '\n'.join([
            'zyrex.node.mining = true', 'zyrex.node.useExternalMiner = true',
            'zyrex.node.offlineGeneration = true', 'scorex.network.nodeName = "zyrex-private-pool"',
            'scorex.network.bindAddress = "0.0.0.0:19532"',
            'scorex.network.declaredAddress = "172.29.95.14:19532"',
            'scorex.network.knownPeers = ["172.29.95.11:19532", "172.29.95.12:19532", "172.29.95.13:19532"]',
            'scorex.restApi.bindAddress = "0.0.0.0:19555"', f'scorex.restApi.apiKeyHash = "{key_hash}"']
            + mining_setting + ['']), public=True)
        config = json.loads(Path("config/pool.json").read_text())
        bind_ip = os.environ.get("ZYREX_POOL_BIND_IP", "127.0.0.1")
        config["publicStratumUrl"] = f"stratum+tcp://{bind_ip}:3333"
        save(FOLDER / "config.json", json.dumps(config, indent=2) + "\n")
        print("Pool config prepared; API credentials saved privately in .local/pool/")
        return
    path = FOLDER / "wallet.json"
    status = api("/wallet/status")
    if not status["isInitialized"]:
        if path.exists():
            saved = json.loads(path.read_text())
            if "mnemonic" not in saved:
                raise RuntimeError("Existing wallet credentials have no mnemonic; inspect the pool node backup")
            api("/wallet/restore", {"pass": saved["password"], "mnemonic": saved["mnemonic"],
                                     "usePre1627KeyDerivation": False})
        else:
            saved = {"password": secrets.token_urlsafe(32)}
            save(path, json.dumps(saved))
            initialized = api("/wallet/init", {"pass": saved["password"]})
            saved["mnemonic"] = initialized["mnemonic"]
            save(path, json.dumps(saved))
    if not path.exists():
        raise RuntimeError("Pool wallet was initialized outside this helper")
    saved = json.loads(path.read_text())
    if not api("/wallet/status")["isUnlocked"]:
        api("/wallet/unlock", {"pass": saved["password"]})
    saved["address"] = api("/wallet/addresses")[0]
    save(path, json.dumps(saved))
    print("Dedicated pool wallet ready:", saved["address"])
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        try:
            info = api("/info")
            work = api("/mining/candidate")
            peers = [json.loads(urllib.request.urlopen(f"http://127.0.0.1:{p}/info", timeout=5).read())
                     for p in (19555, 19556, 19557)]
            target = max(peer["fullHeight"] for peer in peers)
            if info["peersCount"] > 0 and info["fullHeight"] >= target and \
                    info["fullHeight"] == info["headersHeight"] and work["h"] == info["fullHeight"] + 1:
                if "miningPublicKey" not in saved:
                    saved["miningPublicKey"] = work["pk"]
                    save(path, json.dumps(saved))
                if saved["miningPublicKey"] != work["pk"]:
                    raise RuntimeError("Pinned pool mining key does not match the node config")
                print("Pool node synchronized at height", info["fullHeight"])
                return
        except (OSError, KeyError, ValueError):
            pass
        time.sleep(2)
    raise RuntimeError("Pool node synchronization timed out")


if __name__ == "__main__":
    main()
