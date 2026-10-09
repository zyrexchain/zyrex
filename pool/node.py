"""Private node RPC and payout reconciliation. No wallet secrets are served to miners."""
import json
import urllib.error
import urllib.request
from pathlib import Path

from pow import address_bytes
from ledger import allocate_integer, positive_nano


def payout_plan(balances, minimum, fee, limit=50):
    """One actual transaction fee, shared by its recipients in integer units."""
    positive_nano(minimum)
    positive_nano(fee)
    gross = dict((address, positive_nano(amount)) for address, amount in sorted(balances.items())
                 if amount >= minimum)
    gross = dict(list(gross.items())[:limit])
    while gross:
        charges = allocate_integer(fee, gross)
        net = {address: amount - charges[address] for address, amount in gross.items()}
        usable = {address: amount for address, amount in gross.items() if net[address] >= 1_000_000}
        if usable == gross:
            return gross, net, charges
        gross = usable
    return {}, {}, {}


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
        if ledger.halted():
            return
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
            ledger.settle(block, header["id"], reward)

        if ledger.halted():
            return
        # Audit canonical credited blocks before migrating or issuing payments.
        # Newly settled full-reward rounds contribute zero historical fee reserve.
        # The server pins genesis and the mining key before reconciliation.
        ledger.migrate_payout_fees(self.config["feeScriptHex"])
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
        fee = self.config["payoutFeeNano"]
        gross, amounts, charges = payout_plan(ledger.balances(), self.config["minimumPayoutNano"], fee)
        if not amounts:
            return
        balance = self.rpc("/wallet/balances")["balance"]
        if balance < sum(gross.values()):
            return
        tx = self.rpc("/wallet/transaction/generate", {
            "requests": [{"address": a, "value": n} for a, n in amounts.items()], "fee": fee})
        self.validate_payout(tx, amounts, fee)
        ledger.prepare_payout(tx, amounts, gross, charges)
        self.rpc("/transactions", tx)
        ledger.payout_status(tx["id"], "broadcast")

    def validate_payout(self, tx, amounts, fee):
        """Check native signed outputs before reserving any miner's balance."""
        if not isinstance(tx, dict) or not isinstance(tx.get("id"), str) or not tx["id"] or \
                not isinstance(tx.get("outputs"), list):
            raise RuntimeError("Malformed generated payout transaction")
        outputs = [dict(output) for output in tx["outputs"]]
        for output in outputs:
            positive_nano(output.get("value"))
            if not isinstance(output.get("ergoTree"), str) or output.get("assets", []):
                raise RuntimeError("Payout contains an unexpected script or token")
        fee_script = self.config["feeScriptHex"]
        if sum(output["value"] for output in outputs if output["ergoTree"] == fee_script) != fee:
            raise RuntimeError("Generated payout has an unexpected native fee")
        outputs = [output for output in outputs if output["ergoTree"] != fee_script]
        for address, amount in amounts.items():
            script = "0008cd" + address_bytes(address)[1:].hex()
            match = next((output for output in outputs if output["ergoTree"] == script and output["value"] == amount), None)
            if match is None:
                raise RuntimeError("Generated payout does not match its recipient amount")
            outputs.remove(match)
        pool_address = getattr(self, "wallet", {}).get("address")
        change_script = "0008cd" + address_bytes(pool_address)[1:].hex() if pool_address else None
        if len(outputs) > 1 or any(output["ergoTree"] != change_script for output in outputs):
            raise RuntimeError("Generated payout contains an unexpected recipient")
