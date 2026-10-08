"""Private node RPC and payout reconciliation. No wallet secrets are served to miners."""
import json
import urllib.error
import urllib.request
from pathlib import Path

from pow import address_bytes


class Node:
    def __init__(self, config):
        self.config = config
        self.key = Path(config["apiKeyFile"]).read_text().strip()
        self.wallet = json.loads(Path(config["walletFile"]).read_text())
        self.pk = None
        self.reward_script = None

    def rpc(self, path, payload=None):
        request = urllib.request.Request(self.config["nodeUrl"].rstrip("/") + path,
            data=None if payload is None else json.dumps(payload).encode(),
            headers={"api_key": self.key, "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            body = response.read()
            return json.loads(body) if body else None

    def ready(self):
        info = self.rpc("/info")
        network = self.config.get("network", "devnet")
        if info["genesisBlockId"] != self.config["genesisId"] or info["network"] != network:
            raise RuntimeError("Pool RPC is not connected to the pinned Zyrex network")
        # Peer height alone can describe a losing fork. Mine only on an applied
        # best header; a peer advertisement must not stall an otherwise synced node.
        if info["fullHeight"] != info["headersHeight"] or info["bestFullHeaderId"] != info["bestHeaderId"]:
            raise RuntimeError("Pool node is still synchronizing")
        status = self.rpc("/wallet/status")
        if not status["isUnlocked"]:
            self.rpc("/wallet/unlock", {"pass": self.wallet["password"]})
        pk = self.rpc("/mining/rewardPublicKey")["rewardPubkey"]
        if self.pk and self.pk != pk:
            raise RuntimeError("Pool mining public key changed")
        self.pk = pk
        address = self.rpc("/mining/rewardAddress")["rewardAddress"]
        raw = address_bytes(address)
        if raw[0] != self.config.get("addressPrefix", 80) + 3:
            raise RuntimeError("Unexpected mining reward address")
        self.reward_script = raw[1:].hex()
        return info

    def header(self, height):
        # chainSlice follows the best chain; /blocks/at also includes orphan forks.
        headers = self.rpc(f"/blocks/chainSlice?fromHeight={height - 1}&toHeight={height}")
        return next((h for h in headers if h["height"] == height), None)

    def reconcile(self, ledger, height):
        confirmations = self.config["confirmations"]
        for block in ledger.blocks():
            if block["status"] == "orphaned" or height - block["height"] + 1 < confirmations:
                continue
            header = self.header(block["height"])
            matches = header and header["powSolutions"]["pk"] == block["pk"] and header["powSolutions"]["n"] == block["nonce"]
            if block["status"] == "confirmed":
                if not matches or header["id"] != block["hash"]:
                    ledger.halt("A credited block was reorganized. Review balances before resuming payouts.")
                    return
                continue
            if not header:
                break
            if not matches:
                ledger.orphan(block["id"])
                continue
            full = self.rpc("/blocks/" + header["id"])
            txs = full["blockTransactions"]["transactions"]
            reward = sum(output["value"] for tx in txs for output in tx["outputs"]
                         if output["ergoTree"] == self.reward_script)
            ledger.settle(block, header["id"], reward, self.config["payoutFeeNano"])

        if ledger.halted():
            return
        for payout in ledger.payouts():
            try:
                tx = self.rpc("/wallet/transactionById?id=" + payout["id"])
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                tx = None
            if tx and tx.get("numConfirmations", 0) >= confirmations:
                ledger.payout_status(payout["id"], "confirmed")
                continue
            if tx and tx.get("numConfirmations", 0) > 0:
                ledger.payout_status(payout["id"], "broadcast")
                continue
            if payout["status"] == "confirmed":
                ledger.payout_status(payout["id"], "broadcast")
            try:
                self.rpc("/transactions", json.loads(payout["raw"]))
                ledger.payout_status(payout["id"], "broadcast")
            except urllib.error.HTTPError as error:
                # Keep the reservation and signed transaction on every ambiguous response.
                # A tx already in the mempool is not a reason to create another payment.
                if error.code != 400:
                    raise
        if any(p["status"] != "confirmed" for p in ledger.payouts()):
            return
        amounts = {address: amount for address, amount in ledger.balances().items()
                   if amount >= self.config["minimumPayoutNano"]}
        if not amounts:
            return
        # One reserved fee per settled round, one output per credited recipient.
        # Batched transactions cost one fee; unused reserves stay in the pool wallet.
        amounts = dict(list(amounts.items())[:50])
        fee = self.config["payoutFeeNano"]
        balance = self.rpc("/wallet/balances")["balance"]
        if balance < sum(amounts.values()) + fee:
            return
        tx = self.rpc("/wallet/transaction/generate", {
            "requests": [{"address": a, "value": n} for a, n in amounts.items()], "fee": fee})
        ledger.prepare_payout(tx, amounts)
        self.rpc("/transactions", tx)
        ledger.payout_status(tx["id"], "broadcast")
