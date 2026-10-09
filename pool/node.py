"""Private node RPC and payout reconciliation. No wallet secrets are served to miners."""
import json
import urllib.request
from pathlib import Path

from pow import address_bytes
from ledger import allocate_integer, positive_nano
from reconciliation import reconcile_chain, retry_payments, verify_payout_tip
from header_message import header_message


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
        if not isinstance(headers, list):
            raise RuntimeError("Malformed canonical chain response")
        matching = [h for h in headers if isinstance(h, dict) and h.get("height") == height]
        if len(matching) != 1:
            return None
        return matching[0]

    @staticmethod
    def message(header):
        return header_message(header)

    def applied_tip(self):
        """Use current applied state, never a cached height or an unapplied best header."""
        info = self.rpc("/info")
        if not isinstance(info, dict) or info.get("genesisBlockId") != self.config["genesisId"] or \
                info.get("network") != self.config.get("network", "devnet"):
            raise RuntimeError("Pool RPC chain identity is unavailable or changed")
        height = info.get("fullHeight")
        block_id = info.get("bestFullHeaderId")
        if type(height) is not int or height < 1 or height != info.get("headersHeight") or \
                not isinstance(block_id, str) or not block_id or block_id != info.get("bestHeaderId"):
            raise RuntimeError("Pool node has no synchronized applied tip; payouts are paused")
        header = self.header(height)
        if not isinstance(header, dict) or header.get("id") != block_id:
            raise RuntimeError("Applied tip and canonical header disagree; payouts are paused")
        return height, block_id

    def reconcile(self, ledger, height):
        if ledger.halted():
            return
        height, _ = self.applied_tip()
        if not reconcile_chain(self, ledger, height) or ledger.halted():
            return
        # Audit canonical credited blocks before migrating or issuing payments.
        # Newly settled full-reward rounds contribute zero historical fee reserve.
        # The server pins genesis and the mining key before reconciliation.
        ledger.migrate_payout_fees(self.config["feeScriptHex"])
        verify_payout_tip(self, ledger)
        if not retry_payments(self, ledger):
            return
        fee = self.config["payoutFeeNano"]
        gross, amounts, charges = payout_plan(ledger.payable_balances(self.config["minimumPayoutNano"]),
                                              self.config["minimumPayoutNano"], fee)
        if not amounts:
            return
        balance = self.rpc("/wallet/balances")["balance"]
        if balance < sum(gross.values()):
            return
        tx = self.rpc("/wallet/transaction/generate", {
            "requests": [{"address": a, "value": n} for a, n in amounts.items()], "fee": fee})
        self.validate_payout(tx, amounts, fee)
        verify_payout_tip(self, ledger)
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
