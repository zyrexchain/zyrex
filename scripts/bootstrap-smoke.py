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
import threading
import time
import urllib.request


from bootstrap_validation import GENESIS, epoch_evidence, ingress_evidence, sha256_file, source_pin, validate_expected
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
    return validate_expected(json.loads(path.read_text()))


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


def write_config(run, port, p2p_port, api_port, failed_port=None):
    key = secrets.token_hex(32)
    key_hash = hashlib.blake2b(key.encode(), digest_size=32).hexdigest()
    peers = [f"{HOST}:{port}"]
    if failed_port is not None:
        peers.insert(0, f"127.0.0.1:{failed_port}")
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
        "scorex.network.knownPeers = " + json.dumps(peers),
        "scorex.network.peerDiscovery = false",
        "scorex.network.upnpEnabled = false",
        "scorex.network.allowLocal = true",
        "scorex.network.maxConnections = " + str(2 if failed_port is not None else 1),
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


class FailedPeer:
    """A verification-owned failed endpoint; it never changes a public node or gateway."""
    def __init__(self):
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(4)
        self.listener.settimeout(0.1)
        self.port = self.listener.getsockname()[1]
        self.accepted = 0
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, name="zyrex-bootstrap-failed-peer", daemon=True)
        self.thread.start()

    def run(self):
        while not self.stop.is_set():
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            self.accepted += 1
            connection.close()

    def close(self):
        self.stop.set()
        self.listener.close()
        self.thread.join(timeout=2)


def stop_process(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def phase(args, expected, run, command, environment, api_port, key, resolved, port, name, failed_peer=None):
    started = time.monotonic()
    process = None
    try:
        with (run / (name + ".log")).open("x") as log:
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
                            raise RuntimeError("Node connected to an alternative public transport endpoint")
                        peers = api(api_port, "/peers/connected")
                        wallet = api(api_port, "/wallet/status", key)
                        failed_seen = failed_peer is None or failed_peer.accepted > 0
                        if connections and peers and wallet.get("isInitialized") is False and failed_seen:
                            return {"success": True, "network": info["network"], "genesisBlockId": info["genesisBlockId"],
                                    "fullHeight": info["fullHeight"], "headersHeight": info["headersHeight"],
                                    "bestFullHeaderId": info["bestFullHeaderId"], "stateRoot": info["stateRoot"],
                                    "appliedReferenceHeight": expected["fullHeight"],
                                    "appliedReferenceBlockId": expected["bestFullHeaderId"],
                                    "appliedReferenceStateRoot": expected["stateRoot"],
                                    "transportConnections": connections, "advertisedPeers": peers,
                                    "walletInitialized": False, "miningEnabled": False,
                                    "elapsedSeconds": round(time.monotonic() - started, 2)}
                    last_observed = info
                except (OSError, ValueError, KeyError, IndexError) as error:
                    last_observed = {"pending": type(error).__name__}
                time.sleep(1)
            raise RuntimeError("Timed out before state, chosen public endpoint and failed-peer observation were verified: "
                               + json.dumps(last_observed))
    finally:
        stop_process(process)


def verify_port(args, expected, port, parent, fallback=False):
    started = time.monotonic()
    result = {"bootstrap": f"{HOST}:{port}", "freshData": True, "success": False,
              "kind": "failed-peer-fallback" if fallback else "fresh-sync-and-restart"}
    failed_peer = None
    try:
        run = Path(tempfile.mkdtemp(prefix=f"bootstrap-{port}-", dir=parent))
        (run / "data").mkdir(mode=0o700)
        (run / "home").mkdir(mode=0o700)
        if any((run / "data").iterdir()):
            raise RuntimeError("Fresh verification requires an empty node data directory")
        resolved = sorted({normalized_ip(row[4][0]) for row in socket.getaddrinfo(HOST, port, type=socket.SOCK_STREAM)})
        result["dnsAddresses"] = resolved
        p2p_port, api_port = free_port(), free_port()
        while api_port == p2p_port:
            api_port = free_port()
        if fallback:
            failed_peer = FailedPeer()
        config, key = write_config(run, port, p2p_port, api_port, failed_peer.port if failed_peer else None)
        command = [args.java, "-Xms128m", "-Xmx512m", "-XX:ActiveProcessorCount=2", f"-Duser.home={run / 'home'}",
                   "-jar", str(args.jar), "--testnet", "--config", str(config)]
        environment = dict(os.environ)
        environment.pop("DATADIR", None)
        initial = phase(args, expected, run, command, environment, api_port, key, resolved, port, "fresh", failed_peer)
        result.update(initial)
        result["success"] = False
        if fallback:
            result["fallback"] = {"success": True, "failedPeerAcceptedConnections": failed_peer.accepted,
                                  "failedPeerBehavior": "Verification-owned loopback peer accepts and closes TCP",
                                  "remainingBootstrap": f"{HOST}:{port}", "publicGatewayFailureSimulated": False,
                                  "independentInfrastructureVerified": False}
        else:
            if not any((run / "data").rglob("*")):
                raise RuntimeError("Native synchronization created no persistent node data")
            restart_reference = {field: initial[field] for field in
                                 ("network", "genesisBlockId", "fullHeight", "headersHeight", "bestFullHeaderId", "stateRoot")}
            restart = phase(args, restart_reference, run, command, environment, api_port, key, resolved, port, "restart")
            restart.update({"dataPreserved": True, "freshData": False, "sameCanonicalReferenceVerified": True})
            result["restart"] = restart
        result["success"] = True
    except Exception as error:
        result["error"] = str(error)
    finally:
        if failed_peer:
            failed_peer.close()
        result["elapsedSeconds"] = round(time.monotonic() - started, 2)
    return result


def stop_requested(_signal, _frame):
    raise KeyboardInterrupt


def main():
    os.umask(0o077)
    signal.signal(signal.SIGTERM, stop_requested)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=Path("target/scala-2.12/zyrex.jar"))
    parser.add_argument("--java", default="java")
    parser.add_argument("--source-commit", help="Full SHA of the checked-out verification source")
    parser.add_argument("--min-height", type=int, default=0, help="Optional minimum applied checkpoint height")
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
    if not 0 <= args.min_height <= (1 << 31) - 1:
        parser.error("--min-height must be a bounded nonnegative integer")
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    parent = args.output.parent / "runs"
    parent.mkdir(exist_ok=True, mode=0o700)
    if parent.stat().st_mode & 0o077:
        parser.error("Run directory must be accessible only by its owner")
    report = {"success": False, "checks": []}
    try:
        source, dirty = source_pin(args.source_commit)
        jar_hash = sha256_file(args.jar)
        report.update({"sourceCommit": source, "sourceTreeDirty": dirty, "jarSha256": jar_hash,
                       "sourceBinaryLinkVerified": os.environ.get("GITHUB_ACTIONS") == "true" and not dirty,
                       "sourceBinaryProvenance": "CI native assembly" if os.environ.get("GITHUB_ACTIONS") == "true"
                       else "Operator-supplied JAR; source-to-binary correspondence is not independently established"})
        expected = pinned_info(args.expected_info)
        if expected.get("verificationSourceCommit", source) != source or expected.get("verificationJarSha256", jar_hash) != jar_hash:
            raise ValueError("Checkpoint verification source or native binary pin differs")
        if expected["fullHeight"] < args.min_height:
            raise ValueError("Checkpoint is below the requested minimum height")
        report["reference"] = {field: expected[field] for field in
                               ("network", "genesisBlockId", "fullHeight", "headersHeight", "bestFullHeaderId", "stateRoot")}
        report["epochEvidence"] = epoch_evidence(expected["fullHeight"])
        report["minimumHeightGate"] = args.min_height
        ports = tuple(dict.fromkeys(args.port or (19531, 19533)))
        for port in ports:
            report["checks"].append(verify_port(args, expected, port, parent))
        report["checks"].append(verify_port(args, expected, ports[-1], parent, fallback=True))
        report["ingressEvidence"] = ingress_evidence(report["checks"])
        report["success"] = all(check["success"] for check in report["checks"])
    except Exception as error:
        report["error"] = str(error)
    with args.output.open("w") as output:
        os.chmod(args.output, 0o600)
        output.write(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2), flush=True)
    raise SystemExit(0 if report["success"] else 1)


if __name__ == "__main__":
    main()
