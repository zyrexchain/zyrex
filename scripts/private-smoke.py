#!/usr/bin/env python3
"""Verify the running three-node private network without erasing node data."""
import argparse
import json
import subprocess
import time
from pathlib import Path
import urllib.request


def api(port, path):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers={"api_key": "hello"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def until(description, predicate, timeout=600):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            result = predicate()
            if result:
                print(description, flush=True)
                return result
        except (OSError, KeyError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError("Timed out: " + description)


def height(port):
    return api(port, "/info").get("fullHeight") or 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--restart", action="store_true", help="Also restart nodes and check recovery")
    parser.add_argument("--report", default=".local/private-smoke.json")
    args = parser.parse_args()
    ports = [19555, 19556, 19557]
    until("Three nodes have applied mined blocks", lambda: all(height(p) >= 34 for p in ports))
    until("P2P peers connected", lambda: all(api(p, "/info").get("peersCount", 0) > 0 for p in ports))
    target = min(height(p) for p in ports)
    ids = [api(p, f"/blocks/at/{target}")[0] for p in ports]
    if len(set(ids)) != 1:
        raise RuntimeError("Nodes disagree about the chain")
    genesis = json.loads(Path("src/main/resources/genesis/devnet.json").read_text())
    founder_scripts = [out["ergoTree"] for out in genesis["blockTransactions"]["transactions"][0]["outputs"][2:]]
    checked = []
    for h in [1, 2, 16, 17, 33, target]:
        block_id = api(19555, f"/blocks/at/{h}")[0]
        block = api(19555, f"/blocks/{block_id}")
        if block["header"]["version"] != 4:
            raise RuntimeError("A block is not using Autolykos v2")
        outputs = block["blockTransactions"]["transactions"][0]["outputs"]
        subsidy = 100_000_000_000 >> ((h - 1) // 432000)
        expected = [subsidy - 2 * (subsidy // 20), subsidy // 20, subsidy // 20]
        actual = [out["value"] for out in outputs[1:]]
        if actual != expected or [out["ergoTree"] for out in outputs[2:]] != founder_scripts:
            raise RuntimeError(f"Wrong consensus payouts at height {h}")
        checked.append({"height": h, "blockId": block_id, "payoutsNanoZYRX": actual,
                        "difficulty": block["header"]["difficulty"]})
    print("Subsidy outputs and both founder recipients verified", flush=True)
    receiver = api(19556, "/wallet/addresses")[0]
    before = api(19556, "/wallet/balances")["balance"]
    until("Miner has spendable funds", lambda: api(19555, "/wallet/balances")["balance"] >= 2_000_000_000)
    result = subprocess.run(["bin/zyrex-wallet", "send", receiver, "1", "--fee", "0.001"],
                            check=True, capture_output=True, text=True)
    transaction = json.loads(result.stdout)
    until("CLI payment confirmed in receiving wallet", lambda:
          api(19556, "/wallet/balances")["balance"] >= before + 1_000_000_000)
    restarted = False
    if args.restart:
        old_height = height(19557)
        subprocess.run(["docker", "compose", "restart", "node3"], check=True)
        until("Follower recovered after restart", lambda: height(19557) >= old_height)
        old_height = height(19555)
        subprocess.run(["docker", "compose", "restart", "node1"], check=True)
        until("Miner API recovered", lambda: api(19555, "/wallet/status")["isInitialized"])
        subprocess.run(["python3", "scripts/private-wallets.py"], check=True)
        until("Miner resumed without losing data", lambda: height(19555) > old_height)
        until("All peers caught up after miner restart", lambda:
              min(height(p) for p in ports) >= old_height + 1)
        restarted = True
    target = min(height(p) for p in ports)
    chain_ids = [api(p, f"/blocks/at/{target}")[0] for p in ports]
    if len(set(chain_ids)) != 1:
        raise RuntimeError("Chain diverged after transaction or restart")
    report = {"network": "private-testnet", "genesisId": genesis["header"]["id"],
              "sharedHeight": target, "sharedBlockId": chain_ids[0],
              "checkedBlocks": checked, "cliPaymentTxId": transaction,
              "restartVerified": restarted, "gpuVerified": False}
    dest = Path(args.report)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(report, indent=2) + "\n")
    print("PASS: " + str(dest), flush=True)


if __name__ == "__main__":
    main()
