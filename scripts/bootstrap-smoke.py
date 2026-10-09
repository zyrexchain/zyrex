#!/usr/bin/env python3
"""Check each public bootstrap using a fresh, non-mining native UTXO node."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import urllib.request


GENESIS = "0f6e2d9181f10231dafa9e1aa3eb297218204e2e2c38f3c65ae0ab367629d336"
HOST = "zyrexchain.com"
MAX_JSON = 4 * 1024 * 1024
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def api(port, path, key=None):
    headers = {"api_key": key} if key else {}
    request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers=headers)
    with OPENER.open(request, timeout=3) as response:
        data = response.read(MAX_JSON + 1)
    if len(data) > MAX_JSON:
        raise RuntimeError("Native API response exceeded the size bound")
    return json.loads(data)


def pinned_info(path):
    result = json.loads(path.read_text())
    if result.get("network") != "testnet" or result.get("genesisBlockId") != GENESIS:
        raise ValueError("Expected info must pin the Zyrex public testnet genesis")
    height = result.get("fullHeight")
    if type(height) is not int or height <= 1 or result.get("headersHeight", 0) < height:
        raise ValueError("Expected info must include an applied height above genesis")
    for field, length in (("bestFullHeaderId", 64), ("stateRoot", 66)):
        value = result.get(field, "")
        if not isinstance(value, str) or len(value) != length:
            raise ValueError(f"Expected info has an invalid {field}")
        try:
            bytes.fromhex(value)
        except ValueError as error:
            raise ValueError(f"Expected info has an invalid {field}") from error
    return result


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def normalized_ip(value):
    address = ipaddress.ip_address(value)
    return str(address.ipv4_mapped or address) if isinstance(address, ipaddress.IPv6Address) else str(address)


def tcp_address(encoded, family):
    raw, port = encoded.split(":")
    data = bytes.fromhex(raw)
    if family == socket.AF_INET:
        data = data[::-1]
    else:
        data = b"".join(data[index:index + 4][::-1] for index in range(0, 16, 4))
    return normalized_ip(socket.inet_ntop(family, data)), int(port, 16)


def owned_public_sockets(pid):
    """Read transport endpoints owned by this exact child, not advertised peer addresses."""
    inodes = set()
    for descriptor in Path(f"/proc/{pid}/fd").iterdir():
        try:
            target = descriptor.readlink().as_posix()
        except FileNotFoundError:
            continue
        if target.startswith("socket:["):
            inodes.add(target[8:-1])
    connections = []
    for filename, family in (("tcp", socket.AF_INET), ("tcp6", socket.AF_INET6)):
        table = Path(f"/proc/{pid}/net/{filename}")
        for line in table.read_text().splitlines()[1:]:
            fields = line.split()
            if fields[3] != "01" or fields[9] not in inodes:
                continue
            remote_ip, remote_port = tcp_address(fields[2], family)
            if ipaddress.ip_address(remote_ip).is_loopback:
                continue
            connections.append({"remoteIp": remote_ip, "remotePort": remote_port})
    return connections


def write_config(run, port, p2p_port, api_port):
    key = secrets.token_hex(32)
    key_hash = hashlib.blake2b(key.encode(), digest_size=32).hexdigest()
    text = "\n".join([
        f"zyrex.directory = {json.dumps(str(run / 'data'))}",
        "zyrex.node.mining = false",
        "zyrex.node.useExternalMiner = false",
        "zyrex.node.offlineGeneration = false",
        "zyrex.node.stateType = \"utxo\"",
        "zyrex.node.verifyTransactions = true",
        "zyrex.node.blocksToKeep = -1",
        "zyrex.node.utxo.utxoBootstrap = false",
        "zyrex.node.nipopow.nipopowBootstrap = false",
        f"scorex.dataDir = {json.dumps(str(run / 'scorex'))}",
        f"scorex.logDir = {json.dumps(str(run / 'logs'))}",
        f"scorex.network.nodeName = \"zyrex-bootstrap-check-{port}\"",
        f"scorex.network.bindAddress = \"127.0.0.1:{p2p_port}\"",
        f"scorex.network.knownPeers = [\"{HOST}:{port}\"]",
        "scorex.network.peerDiscovery = false",
        "scorex.network.upnpEnabled = false",
        "scorex.network.allowLocal = true",
        "scorex.network.maxConnections = 1",
        f"scorex.restApi.bindAddress = \"127.0.0.1:{api_port}\"",
        f"scorex.restApi.apiKeyHash = \"{key_hash}\"",
        "",
    ])
    config = run / "node.conf"
    with config.open("x") as output:
        os.chmod(config, 0o600)
        output.write(text)
    return config, key


def applied_baseline(api_port, expected):
    info = api(api_port, "/info")
    if info.get("network") != "testnet" or info.get("genesisBlockId") != GENESIS:
        return None
    full_height = info.get("fullHeight") or 0
    if full_height < expected["fullHeight"] or info.get("headersHeight") != full_height:
        return None
    if info.get("isMining") is not False or info.get("stateType") != "utxo":
        raise RuntimeError("Verification node must apply UTXO state with mining disabled")
    if api(api_port, "/blocks/at/1")[0] != GENESIS:
        raise RuntimeError("Applied genesis differs from the public testnet pin")
    ids = api(api_port, f"/blocks/at/{expected['fullHeight']}")
    if not ids or ids[0] != expected["bestFullHeaderId"]:
        raise RuntimeError("Canonical block at the reference height differs")
    block = api(api_port, f"/blocks/{ids[0]}")
    header = block["header"]
    if header["height"] != expected["fullHeight"] or header["stateRoot"] != expected["stateRoot"]:
        raise RuntimeError("Downloaded full block differs from the reference state root")
    if not block["blockTransactions"]["transactions"]:
        raise RuntimeError("Reference full block has no transactions")
    if full_height == expected["fullHeight"] and (
        info.get("bestFullHeaderId") != expected["bestFullHeaderId"] or info.get("stateRoot") != expected["stateRoot"]
    ):
        raise RuntimeError("Applied state differs from the reference tip")
    return info


def verify_port(args, expected, port, parent):
    started = time.monotonic()
    run = Path(tempfile.mkdtemp(prefix=f"bootstrap-{port}-", dir=parent))
    (run / "data").mkdir(mode=0o700)
    (run / "home").mkdir(mode=0o700)
    resolved = sorted({normalized_ip(row[4][0]) for row in socket.getaddrinfo(HOST, port, type=socket.SOCK_STREAM)})
    p2p_port, api_port = free_port(), free_port()
    while api_port == p2p_port:
        api_port = free_port()
    config, key = write_config(run, port, p2p_port, api_port)
    command = [
        args.java, "-Xms128m", "-Xmx512m", "-XX:ActiveProcessorCount=2", f"-Duser.home={run / 'home'}",
        "-jar", str(args.jar), "--testnet", "--config", str(config),
    ]
    environment = dict(os.environ)
    environment.pop("DATADIR", None)
    result = {"bootstrap": f"{HOST}:{port}", "dnsAddresses": resolved, "freshData": True, "success": False}
    process = None
    try:
        with (run / "native.log").open("x") as log:
            process = subprocess.Popen(command, cwd=run, env=environment, stdout=log, stderr=subprocess.STDOUT)
            deadline = started + args.timeout
            last_observed = None
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"Native node exited with status {process.returncode}")
                try:
                    info = applied_baseline(api_port, expected)
                    if info:
                        connections = owned_public_sockets(process.pid)
                        allowed = [{"remoteIp": address, "remotePort": port} for address in resolved]
                        if any(connection not in allowed for connection in connections):
                            raise RuntimeError("Verification node connected to an alternative public transport endpoint")
                        peers = api(api_port, "/peers/connected")
                        wallet = api(api_port, "/wallet/status", key)
                        if connections and peers and wallet.get("isInitialized") is False:
                            result.update({
                                "success": True,
                                "network": info["network"],
                                "genesisBlockId": info["genesisBlockId"],
                                "fullHeight": info["fullHeight"],
                                "headersHeight": info["headersHeight"],
                                "bestFullHeaderId": info["bestFullHeaderId"],
                                "stateRoot": info["stateRoot"],
                                "appliedReferenceHeight": expected["fullHeight"],
                                "appliedReferenceBlockId": expected["bestFullHeaderId"],
                                "appliedReferenceStateRoot": expected["stateRoot"],
                                "transportConnections": connections,
                                "advertisedPeers": peers,
                                "walletInitialized": False,
                                "miningEnabled": False,
                                "elapsedSeconds": round(time.monotonic() - started, 2),
                            })
                            return result
                    last_observed = info
                except (OSError, ValueError, KeyError, IndexError) as error:
                    last_observed = {"pending": type(error).__name__}
                time.sleep(1)
            result["lastObserved"] = last_observed
            raise RuntimeError("Timed out before native state and the exact public transport endpoint were verified")
    except Exception as error:
        result.update({"error": str(error), "elapsedSeconds": round(time.monotonic() - started, 2)})
        return result
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


def stop_requested(_signal, _frame):
    raise KeyboardInterrupt


def main():
    os.umask(0o077)
    signal.signal(signal.SIGTERM, stop_requested)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=Path("target/scala-2.12/zyrex.jar"))
    parser.add_argument("--java", default="java")
    parser.add_argument("--expected-info", type=Path, required=True, help="Public native reference info JSON")
    parser.add_argument("--port", type=int, choices=(19531, 19533), action="append", help="Default: check both independently")
    parser.add_argument("--timeout", type=int, default=180, help="Maximum startup and synchronization seconds per bootstrap")
    parser.add_argument("--output", type=Path, default=Path(".local/bootstrap-smoke/report.json"))
    args = parser.parse_args()
    if not 10 <= args.timeout <= 600:
        parser.error("--timeout must be between 10 and 600 seconds")
    args.jar = args.jar.resolve(strict=True)
    if shutil.which(args.java) is None:
        parser.error("Java executable is unavailable")
    expected = pinned_info(args.expected_info)
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    parent = args.output.parent / "runs"
    parent.mkdir(exist_ok=True, mode=0o700)
    if parent.stat().st_mode & 0o077:
        parser.error("Run directory must be accessible only by its owner")
    digest = hashlib.sha256()
    with args.jar.open("rb") as jar:
        for chunk in iter(lambda: jar.read(1024 * 1024), b""):
            digest.update(chunk)
    checks = [verify_port(args, expected, port, parent) for port in dict.fromkeys(args.port or (19531, 19533))]
    report = {
        "success": all(check["success"] for check in checks), "jarSha256": digest.hexdigest(),
        "reference": {field: expected[field] for field in
                      ("network", "genesisBlockId", "fullHeight", "headersHeight", "bestFullHeaderId", "stateRoot")},
        "checks": checks,
    }
    with args.output.open("w") as output:
        os.chmod(args.output, 0o600)
        output.write(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2), flush=True)
    raise SystemExit(0 if report["success"] else 1)


if __name__ == "__main__":
    main()
