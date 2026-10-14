"""Actual payout amounts, bounded signed-size repricing and immutable retry coverage."""
import hashlib
import json
import unittest
import urllib.error
from unittest.mock import Mock, patch

from fee_policy import FeeUnavailable, MAX_NANO, policy, prepare_payment, quoted_fee
from ledger import Ledger
from node import Node, payout_plan
from reconciliation import audit_block
from test_pool import ADDRESS1, ADDRESS2, FEE_SCRIPT, MESSAGE, PK, payment


class AutoPayoutFees(unittest.TestCase):
    def setUp(self):
        self.node = object.__new__(Node)
        self.node.config = {"payoutFeeMode": "auto", "payoutFeeNano": 1_000_000,
                            "maximumPayoutFeeNano": 10_000_000, "minimumPayoutNano": 1_000_000_000,
                            "feeScriptHex": FEE_SCRIPT, "confirmations": 5}
        self.node.wallet = {"address": ADDRESS1}
        self.balances = {ADDRESS1: 3_000_000_000, ADDRESS2: 7_000_000_000}
        self.generated, self.quoted, self.broadcast = [], [], []
        self.requirements = [1_000_000]
        self.node.rpc = Mock(side_effect=self.rpc)

    def rpc(self, path, payload=None):
        if path == "/wallet/balances":
            return {"balance": 10_000_000_000}
        if path == "/wallet/transaction/generate":
            ident = hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
            tx = payment(ident, {row["address"]: row["value"] for row in payload["requests"]}, payload["fee"])
            tx["inputs"] = [{"spendingProof": {"proofBytes": "010203"}}]
            self.generated.append(tx)
            return tx
        if path.startswith("/transactions/feeQuote"):
            self.quoted.append(payload)
            index = min(len(self.quoted) - 1, len(self.requirements) - 1)
            return self.quote(payload, self.requirements[index])
        if path == "/transactions":
            self.broadcast.append(payload)
            return payload["id"]
        if path.startswith("/wallet/transactionById"):
            raise urllib.error.HTTPError(path, 404, "Not found", None, None)
        raise AssertionError("Unexpected RPC: " + path)

    @staticmethod
    def quote(tx, required):
        return {"quoteVersion": 1, "feeNano": str(required), "minimumFeeNano": "1000000", "txSize": 512,
                "maxTransactionSize": 1048576, "txId": tx["id"], "feeScriptHex": FEE_SCRIPT,
                "paidFeeNano": str(sum(output["value"] for output in tx["outputs"] if output["ergoTree"] == FEE_SCRIPT))}

    def prepare(self):
        return prepare_payment(self.node, self.balances, payout_plan)

    def test_congested_quote_rebuilds_before_credit_reservation_and_preserves_every_unit(self):
        self.requirements = [2_000_001, 2_000_001]
        tx, gross, net, fees = self.prepare()
        self.assertEqual(sum(fees.values()), 2_000_001)
        self.assertEqual(sum(net.values()) + sum(fees.values()), sum(gross.values()))
        self.assertEqual(fees, {ADDRESS1: 600_000, ADDRESS2: 1_400_001})
        self.assertEqual(tx, self.generated[-1])
        self.assertNotEqual(self.generated[0]["id"], tx["id"])
        self.assertEqual(self.quoted, self.generated)
        self.assertEqual(self.broadcast, [])

    def test_quote_drop_keeps_already_generated_fee_and_exact_signed_id(self):
        self.requirements = [2_000_000, 1_000_000]
        tx, _, _, fees = self.prepare()
        self.assertEqual(sum(fees.values()), 2_000_000)
        self.assertEqual(tx["id"], self.generated[-1]["id"])
        self.assertEqual(len(self.generated), 2)

    def test_fee_ceiling_and_oscillation_do_not_broadcast_or_reserve(self):
        for required in ([10_000_001], [2_000_000, 3_000_000, 4_000_000, 5_000_000]):
            with self.subTest(required=required):
                self.requirements = required
                self.generated.clear(); self.quoted.clear()
                with self.assertRaises(FeeUnavailable):
                    self.prepare()
                self.assertLessEqual(len(self.generated), 4)
                self.assertEqual(self.broadcast, [])
                self.assertEqual(self.balances, {ADDRESS1: 3_000_000_000, ADDRESS2: 7_000_000_000})

    def test_signed_quote_identity_amount_script_and_byte_bounds_are_checked(self):
        tx = payment("a" * 64, {ADDRESS1: 1_000_000_000}, 1_000_000)
        good = self.quote(tx, 1_000_000)
        changes = ({"quoteVersion": True}, {"quoteVersion": 2}, {"txId": "b" * 64}, {"paidFeeNano": "2000000"},
                   {"feeScriptHex": "1002"}, {"feeNano": 1_000_000}, {"feeNano": str(MAX_NANO + 1)},
                   {"feeNano": "-1"}, {"feeNano": "01"}, {"txSize": True}, {"txSize": 0},
                   {"txSize": 1048577}, {"minimumFeeNano": "2000000"})
        for change in changes:
            with self.subTest(change=change), self.assertRaises(FeeUnavailable):
                quoted_fee(dict(good, **change), tx, FEE_SCRIPT, 1_000_000)

    def test_zero_native_floor_keeps_configured_positive_fee(self):
        tx = payment("a" * 64, {ADDRESS1: 1_000_000_000}, 1_000_000)
        q = dict(self.quote(tx, 0), minimumFeeNano="0")
        self.assertEqual(quoted_fee(q, tx, FEE_SCRIPT, 1_000_000), 0)

    def test_manual_and_legacy_configs_do_not_call_fee_estimator(self):
        for mode in (None, "fixed"):
            with self.subTest(mode=mode):
                self.node.config.pop("payoutFeeMode", None)
                if mode:
                    self.node.config["payoutFeeMode"] = mode
                self.prepare()
                self.assertEqual(self.quoted, [])

    def test_old_node_only_missing_quote_route_uses_configured_fee(self):
        base = self.rpc
        def absent(path, payload=None):
            if path.startswith("/transactions/feeQuote"):
                raise urllib.error.HTTPError(path, 404, "Not found", None, None)
            return base(path, payload)
        self.node.rpc = Mock(side_effect=absent)
        _, _, _, fees = self.prepare()
        self.assertEqual(sum(fees.values()), 1_000_000)
        for code in (400, 401, 429, 500, 503):
            def failure(path, payload=None):
                if path.startswith("/transactions/feeQuote"):
                    raise urllib.error.HTTPError(path, code, "Unavailable", None, None)
                return base(path, payload)
            self.node.rpc = Mock(side_effect=failure)
            with self.subTest(code=code), self.assertRaises(urllib.error.HTTPError):
                self.prepare()
        self.assertEqual(self.broadcast, [])

    def test_invalid_auto_configuration_cannot_expand_protocol_amount_bounds(self):
        for change in ({"payoutFeeMode": "other"}, {"maximumPayoutFeeNano": True},
                       {"maximumPayoutFeeNano": 999999}, {"maximumPayoutFeeNano": MAX_NANO + 1}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                policy(dict(self.node.config, **change))

    def test_pending_signed_payout_is_replayed_without_any_new_quote_or_generation(self):
        ledger = Ledger(":memory:")
        try:
            ledger.bind_network("f" * 64, PK)
            end = ledger.share(MESSAGE, "nonce", ADDRESS1, "worker", 1)
            ledger.block({"msg": MESSAGE, "h": 1, "pk": PK}, "nonce", end)
            ledger.settle(ledger.blocks()[0], "block-id", 90_000_000_000)
            ledger.migrate_payout_fees(FEE_SCRIPT)
            gross, net, fees = payout_plan(ledger.balances(), 1_000_000_000, 1_000_000)
            saved = payment("c" * 64, net, 1_000_000)
            ledger.prepare_payout(saved, net, gross, fees)
            self.node.applied_tip = Mock(return_value=(8, "tip"))
            self.requirements = [9_000_000]
            with patch("node.reconcile_chain", return_value=True), patch("node.verify_payout_tip"):
                self.node.reconcile(ledger, 8)
            self.assertEqual(self.generated, [])
            self.assertEqual(self.quoted, [])
            self.assertEqual(self.broadcast, [saved])
            self.assertEqual(ledger.payouts()[0]["id"], saved["id"])
            self.assertEqual(json.loads(ledger.payouts()[0]["fees"]), fees)
        finally:
            ledger.db.close()

    def test_native_subsidy_and_separate_fee_outputs_both_enter_prop_credit(self):
        ledger = Ledger(":memory:")
        try:
            for address, weight in ((ADDRESS1, 1), (ADDRESS2, 3)):
                end = ledger.share(MESSAGE, address, address, "worker", weight)
            ledger.block({"msg": MESSAGE, "h": 1, "pk": PK}, "nonce", end)
            header = {"id": "d" * 64, "powSolutions": {"pk": PK, "n": "nonce"}}
            self.node.header = Mock(return_value=header)
            self.node.message = Mock(return_value=MESSAGE)
            self.node.reward_script = "reward-script"
            self.node.rpc = Mock(return_value={"blockTransactions": {"transactions": [
                {"outputs": [{"ergoTree": "reward-script", "value": 90_000_000_000},
                             {"ergoTree": "team-script", "value": 10_000_000_000}]},
                {"outputs": [{"ergoTree": "reward-script", "value": 2_000_000}]}]}})
            audit_block(self.node, ledger, ledger.blocks()[0], 6)
            self.assertEqual(ledger.balances(), {ADDRESS1: 22_500_500_000, ADDRESS2: 67_501_500_000})
        finally:
            ledger.db.close()


if __name__ == "__main__":
    unittest.main()
