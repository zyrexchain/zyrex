"""Deterministic reorg, restart, ambiguous-RPC and bounded-history payout regressions."""
import tempfile
import json
import unittest
import urllib.error
from pathlib import Path

from ledger import Ledger
from node import Node
from query_budget import PublicQueryLimit, query_budget
from reconciliation import ChainUncertain, reconcile_chain
from test_pool import ADDRESS1, ADDRESS2, FEE_SCRIPT, MESSAGE, PK, payment
from header_message import header_message


class ChainFixture:
    def __init__(self):
        self.headers = {}
        self.calls = []
        self.transactions = {}
        self.ambiguous = set()
        self.broadcasts = []
        self.generated = []
        self.on_generate = None
        self.tip_suffix = ""
        self.height = 10
        self.reward = 100
        self.info_override = None
        self.node = object.__new__(Node)
        self.node.config = {"genesisId": "genesis", "confirmations": 5, "minimumPayoutNano": 1_000_000_000,
                            "payoutFeeNano": 1_000_000, "feeScriptHex": FEE_SCRIPT}
        self.node.reward_script = "reward"
        self.node.header, self.node.rpc = self.header, self.rpc

    def header(self, height):
        self.calls.append(("header", height))
        return self.headers.get(height, self.native_header(height, "other", "header-" + str(height) + self.tip_suffix, "another miner"))

    @staticmethod
    def native_header(height, nonce="a", block_hash="canonical", pk=PK):
        return {"id": block_hash, "version": 2, "parentId": "11" * 32, "adProofsRoot": "22" * 32,
                "transactionsRoot": "33" * 32, "stateRoot": "44" * 33, "timestamp": 1000 + height,
                "extensionHash": "55" * 32, "nBits": 0x01010000, "height": height,
                "votes": "000000", "unparsedBytes": "", "powSolutions": {"pk": pk, "n": nonce}}

    def canonical(self, height, nonce, block_hash=None):
        self.headers[height] = self.native_header(height, nonce, block_hash or "found-" + nonce)

    def rpc(self, path, payload=None):
        self.calls.append((path, payload))
        if path in self.ambiguous:
            return None
        if path == "/info":
            return self.info_override or {"genesisBlockId": "genesis", "network": "devnet",
                "fullHeight": self.height, "headersHeight": self.height,
                "bestFullHeaderId": self.header(self.height)["id"], "bestHeaderId": self.header(self.height)["id"]}
        if path.startswith("/blocks/"):
            return {"blockTransactions": {"transactions": [{"outputs": [{"value": self.reward, "ergoTree": "reward"}]}]}}
        if path.startswith("/wallet/transactionById?id="):
            txid = path.split("=", 1)[1]
            if txid not in self.transactions:
                raise urllib.error.HTTPError(path, 404, "not found", None, None)
            return self.transactions[txid]
        if path == "/wallet/balances":
            return {"balance": 1_000_000_000_000}
        if path == "/wallet/transaction/generate":
            self.generated.append(payload)
            if self.on_generate:
                self.on_generate()
            amounts = {r["address"]: r["value"] for r in payload["requests"]}
            return payment("new-payment", amounts, payload["fee"])
        if path == "/transactions":
            self.broadcasts.append(payload["id"])
            return payload["id"]
        raise AssertionError(path)


class ReconciliationTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.path = str(Path(self.folder.name) / "pool.sqlite")
        self.ledger = Ledger(self.path)
        self.ledger.bind_network("genesis", PK)
        self.chain = ChainFixture()

    def tearDown(self):
        self.ledger.db.close()
        self.folder.cleanup()

    def found(self, address=ADDRESS1, nonce="a", height=5):
        message = header_message(self.chain.native_header(height))
        share = self.ledger.share(message, nonce, address, "worker", 1)
        self.ledger.block({"h": height, "msg": message, "pk": PK}, nonce, share)
        return self.ledger.blocks()[-1]

    def restart(self):
        self.ledger.db.close()
        self.ledger = Ledger(self.path)
        self.ledger.bind_network("genesis", PK)

    def reconcile(self, height):
        self.chain.height = height
        return self.chain.node.reconcile(self.ledger, height)

    def test_submitted_orphan_canonical_credits_exactly_once_across_restart(self):
        self.found()
        self.reconcile(10)
        self.assertEqual(self.ledger.blocks()[0]["status"], "orphaned")
        self.assertEqual(self.ledger.balances(), {})
        self.restart()
        self.chain.canonical(5, "a")
        self.chain.tip_suffix = "new-branch"
        self.reconcile(10)
        self.reconcile(10)
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 100})
        self.assertEqual(self.ledger.blocks()[0]["status"], "confirmed")
        self.restart()
        for _ in range(3):
            self.reconcile(11)
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 100})
        self.assertEqual(self.ledger.db.execute("SELECT COUNT(*) FROM credits").fetchone()[0], 1)

    def test_same_height_nonce_different_templates_credit_and_pay_one_canonical_reward(self):
        nonce = "0100000000000000"
        wrong_message = "00" * 32
        first = self.ledger.share(wrong_message, nonce, ADDRESS1, "worker", 1)
        self.ledger.block({"h": 5, "msg": wrong_message, "pk": PK}, nonce, first)
        self.found(nonce=nonce)
        self.chain.canonical(5, nonce, "one-native-block")
        self.chain.reward = 2_000_000_000
        self.reconcile(10)
        blocks = self.ledger.blocks()
        self.assertEqual([b["status"] for b in blocks], ["orphaned", "confirmed"])
        self.assertEqual(sum(r[0] for r in self.ledger.db.execute("SELECT amount FROM credits")), self.chain.reward)
        self.assertEqual(self.ledger.db.execute("SELECT COUNT(*) FROM canonical_claims").fetchone()[0], 1)
        self.assertEqual(len(self.ledger.payouts()), 1)
        self.assertEqual(json.loads(self.ledger.payouts()[0]["gross"]), {ADDRESS1: self.chain.reward})
        self.assertEqual(self.chain.broadcasts, ["new-payment"])
        self.chain.transactions["new-payment"] = {"numConfirmations": 5}
        self.restart()
        self.reconcile(10)
        self.assertEqual(len(self.ledger.payouts()), 1)
        self.assertEqual(self.chain.broadcasts, ["new-payment"])
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 0})

    def test_canonical_hash_claim_atomically_prevents_duplicate_direct_credit(self):
        first = self.found()
        self.ledger.settle(first, "one-native-block", 100)
        second = self.found(nonce="b")
        self.ledger.settle(second, "one-native-block", 100)
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 100})
        self.assertEqual(self.ledger.db.execute("SELECT COUNT(*) FROM credits").fetchone()[0], 1)
        self.assertEqual(self.ledger.db.execute("SELECT COUNT(*) FROM canonical_claims").fetchone()[0], 1)
        self.assertIsNotNone(self.ledger.halted())
        self.restart()
        self.reconcile(10)
        self.assertEqual(self.chain.broadcasts, [])

    def test_missing_claim_after_older_software_cannot_recredit_a_case_alias(self):
        first = self.found()
        self.ledger.settle(first, "one-native-block", 100)
        with self.ledger.db:
            self.ledger.db.execute("DELETE FROM canonical_claims")
        second = self.found(nonce="b")
        self.ledger.settle(second, "ONE-NATIVE-BLOCK", 100)
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 100})
        self.assertEqual(self.ledger.db.execute("SELECT COUNT(*) FROM credits").fetchone()[0], 1)
        self.assertIsNotNone(self.ledger.halted())

    def test_legacy_duplicate_hashes_halt_on_restart_without_financial_history_rewrite(self):
        first = self.found()
        self.ledger.settle(first, "duplicated-native-block", 100)
        second = self.found(nonce="b")
        with self.ledger.db:
            self.ledger.db.execute("UPDATE blocks SET status='confirmed',hash=?,reward=100 WHERE id=?",
                                   ("duplicated-native-block", second["id"]))
            self.ledger.db.execute("INSERT INTO credits VALUES(?,?,100)", (second["id"], ADDRESS1))
            self.ledger.db.execute("UPDATE balances SET amount=amount+100 WHERE address=?", (ADDRESS1,))
        self.ledger.prepare_payout({"id": "historical-signed"}, {ADDRESS1: 50})
        tables = ("blocks", "credits", "balances", "payouts", "shares")
        before = {table: [tuple(r) for r in self.ledger.db.execute("SELECT * FROM " + table)] for table in tables}
        self.restart()
        self.assertIsNotNone(self.ledger.halted())
        self.assertEqual(before, {table: [tuple(r) for r in self.ledger.db.execute("SELECT * FROM " + table)] for table in tables})
        events = self.ledger.db.execute("SELECT COUNT(*) FROM chain_events").fetchone()[0]
        self.reconcile(10)
        self.restart()
        self.assertEqual(self.ledger.db.execute("SELECT COUNT(*) FROM chain_events").fetchone()[0], events)
        self.assertEqual(self.chain.broadcasts, [])

    def test_native_scala_message_and_header_bytes_vectors(self):
        from header_message import bytes_without_pow
        fixture = json.loads(Path(__file__).with_name("header_vectors.json").read_text())
        for vector in fixture["vectors"]:
            with self.subTest(vector=vector["name"]):
                self.assertEqual(bytes_without_pow(vector["header"]).hex(), vector["bytesWithoutPow"])
                self.assertEqual(header_message(vector["header"]), vector["msg"])
        for vector in fixture["invalid"]:
            with self.subTest(invalid=vector["name"]):
                with self.assertRaises((ValueError, TypeError, KeyError)):
                    header_message(vector["header"])

    def test_orphan_return_after_work_carried_does_not_steal_other_round(self):
        orphan = self.found()
        self.ledger.orphan(orphan["id"])
        later = self.found(ADDRESS2, "b", 6)
        self.ledger.settle(later, "later", 100)
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 50, ADDRESS2: 50})
        before = [tuple(r) for r in self.ledger.db.execute("SELECT * FROM credits WHERE block=?", (later["id"],))]
        self.restart()
        self.ledger.settle(orphan, "returned", 100)
        self.ledger.settle(orphan, "returned", 100)
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 150, ADDRESS2: 50})
        self.assertEqual(before, [tuple(r) for r in self.ledger.db.execute("SELECT * FROM credits WHERE block=?", (later["id"],))])
        self.assertEqual(sum(r[0] for r in self.ledger.db.execute("SELECT amount FROM credits")), 200)

    def test_old_orphan_without_snapshot_recovers_consumed_weights(self):
        orphan = self.found()
        with self.ledger.db:
            self.ledger.db.execute("UPDATE blocks SET status='orphaned' WHERE id=?", (orphan["id"],))
        later = self.found(ADDRESS2, "b", 6)
        self.ledger.settle(later, "later", 100)
        self.ledger.settle(orphan, "returned", 100)
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 150, ADDRESS2: 50})

    def test_credited_mismatch_below_confirmation_depth_halts_without_rewriting_history(self):
        block = self.found()
        self.ledger.settle(block, "original", 100)
        self.ledger.prepare_payout({"id": "reserved"}, {ADDRESS1: 80})
        before = {table: [tuple(r) for r in self.ledger.db.execute("SELECT * FROM " + table)]
                  for table in ("credits", "payouts", "balances")}
        self.chain.canonical(5, "a", "different")
        self.reconcile(5)
        self.assertIsNotNone(self.ledger.halted())
        self.assertEqual(before, {table: [tuple(r) for r in self.ledger.db.execute("SELECT * FROM " + table)] for table in before})
        self.assertEqual(self.ledger.db.execute("SELECT COUNT(*) FROM chain_events").fetchone()[0], 1)
        self.restart()
        self.reconcile(20)
        self.assertEqual(self.chain.broadcasts, [])
        self.assertEqual(self.ledger.db.execute("SELECT COUNT(*) FROM chain_events").fetchone()[0], 1)

    def test_temporary_height_drop_pauses_until_maturity_recovers(self):
        self.found()
        self.chain.canonical(5, "a")
        self.reconcile(10)
        before = self.ledger.balances()
        with self.assertRaises(ChainUncertain):
            self.reconcile(6)
        self.assertEqual(before, self.ledger.balances())
        self.assertIsNone(self.ledger.halted())
        self.restart()
        self.reconcile(10)
        self.assertEqual(before, self.ledger.balances())

    def test_missing_and_malformed_canonical_headers_never_plan_a_payment(self):
        block = self.found()
        self.ledger.settle(block, "original", 100)
        for response in (None, {}, {"id": "original", "powSolutions": {}}):
            self.chain.headers[5] = response
            with self.assertRaises(ChainUncertain):
                self.reconcile(10)
        self.assertEqual(self.chain.generated, [])
        self.assertEqual(self.chain.broadcasts, [])
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 100})

    def test_unknown_wallet_confirmation_does_not_rebroadcast_or_plan(self):
        self.ledger.migrate_payout_fees(FEE_SCRIPT)
        self.ledger.db.execute("INSERT INTO balances VALUES(?,100)", (ADDRESS1,))
        self.ledger.db.commit()
        self.ledger.prepare_payout({"id": "signed"}, {ADDRESS1: 50})
        self.chain.ambiguous.add("/wallet/transactionById?id=signed")
        with self.assertRaises(ChainUncertain):
            self.reconcile(10)
        self.assertEqual(self.chain.broadcasts, [])
        self.assertEqual(self.chain.generated, [])
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 50})

    def test_confirmed_payment_removed_by_reorg_reuses_original_signed_transaction(self):
        self.ledger.migrate_payout_fees(FEE_SCRIPT)
        with self.ledger.db:
            self.ledger.db.execute("INSERT INTO balances VALUES(?,100)", (ADDRESS1,))
        self.ledger.prepare_payout({"id": "signed"}, {ADDRESS1: 50})
        self.ledger.payout_status("signed", "confirmed")
        self.chain.transactions["signed"] = {"numConfirmations": 10}
        self.reconcile(10)
        del self.chain.transactions["signed"]
        self.chain.tip_suffix = "replacement"
        self.reconcile(10)
        self.assertEqual(self.chain.broadcasts, [])
        self.restart()
        self.reconcile(10)
        self.assertEqual(self.chain.broadcasts, ["signed"])
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 50})
        self.assertEqual(len(self.ledger.payouts()), 1)

    def test_branch_change_during_generate_leaves_no_new_reservation_or_broadcast(self):
        self.ledger.migrate_payout_fees(FEE_SCRIPT)
        with self.ledger.db:
            self.ledger.db.execute("INSERT INTO balances VALUES(?,2000000000)", (ADDRESS1,))
        self.chain.on_generate = lambda: setattr(self.chain, "tip_suffix", "changed")
        with self.assertRaises(ChainUncertain):
            self.reconcile(10)
        self.assertEqual(self.ledger.payouts(), [])
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 2_000_000_000})
        self.assertEqual(self.chain.broadcasts, [])

    def test_cached_height_cannot_settle_an_immature_applied_block(self):
        self.found()
        self.chain.canonical(5, "a")
        self.chain.height = 6
        self.chain.node.reconcile(self.ledger, 100)
        self.assertEqual(self.ledger.balances(), {})
        self.assertEqual(self.ledger.blocks()[0]["status"], "submitted")

    def test_unapplied_headers_and_changed_chain_identity_never_credit_or_pay(self):
        self.found()
        self.chain.canonical(5, "a")
        valid = {"genesisBlockId": "genesis", "network": "devnet", "fullHeight": 10,
                 "headersHeight": 10, "bestFullHeaderId": "header-10", "bestHeaderId": "header-10"}
        for changes in ({"fullHeight": 6}, {"bestHeaderId": "unapplied"},
                        {"genesisBlockId": "another-genesis"}, {"network": "another-network"}):
            self.chain.info_override = dict(valid, **changes)
            with self.assertRaises(RuntimeError):
                self.reconcile(100)
        self.assertEqual(self.ledger.balances(), {})
        self.assertEqual(self.chain.generated, [])
        self.assertEqual(self.chain.broadcasts, [])

    def test_applied_height_drop_after_generate_does_not_reserve_or_broadcast(self):
        self.ledger.migrate_payout_fees(FEE_SCRIPT)
        with self.ledger.db:
            self.ledger.db.execute("INSERT INTO balances VALUES(?,2000000000)", (ADDRESS1,))
        self.chain.on_generate = lambda: setattr(self.chain, "height", 6)
        with self.assertRaises(ChainUncertain):
            self.reconcile(10)
        self.assertEqual(self.ledger.payouts(), [])
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 2_000_000_000})
        self.assertEqual(self.chain.broadcasts, [])

    def seed_history(self, count):
        self.ledger.migrate_payout_fees(FEE_SCRIPT)
        with self.ledger.db:
            for number in range(1, count + 1):
                nonce = "historical-" + str(number)
                message = header_message(self.chain.native_header(number))
                self.ledger.db.execute("""INSERT INTO shares(msg,nonce,address,worker,weight,created,round)
                    VALUES(?,?,?,?,?,?,?)""", (message, nonce, ADDRESS1, "worker", "1", 1, number))
                self.ledger.db.execute("""INSERT INTO blocks(id,height,msg,nonce,pk,end_share,status,hash,reward,created)
                    VALUES(?,?,?,?,?,?,'confirmed',?,100,1)""", (number, number, message, nonce, PK, number, "found-" + nonce))
                self.ledger.db.execute("INSERT INTO credits VALUES(?,?,100)", (number, ADDRESS1))
                self.chain.canonical(number, nonce)
            self.ledger.db.execute("INSERT INTO balances VALUES(?,?)", (ADDRESS1, count * 100))

    def test_large_history_checks_each_record_in_bounded_cycles_and_persists_cursor(self):
        self.seed_history(1000)
        maximum = 0
        for cycle in range(66):
            start = len(self.chain.calls)
            self.reconcile(2000)
            maximum = max(maximum, len(self.chain.calls) - start)
            if cycle == 8:
                self.assertTrue(self.ledger.chain_state()["scanning"])
                self.assertGreater(self.ledger.chain_state()["rescanBlock"], 0)
                self.restart()
        self.assertLessEqual(maximum, 70)
        self.assertFalse(self.ledger.chain_state()["scanning"])
        inspected = {height for kind, height in self.chain.calls if kind == "header"}
        self.assertTrue(set(range(1, 1001)).issubset(inspected))
        self.chain.tip_suffix = "deep-reorg"
        self.chain.canonical(1, "wrong", "replacement-at-one")
        self.reconcile(2000)
        self.assertIsNotNone(self.ledger.halted())
        self.assertEqual(self.chain.generated, [])
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 100_000})

    def test_second_branch_change_during_catchup_rechecks_already_scanned_credits(self):
        self.seed_history(100)
        self.reconcile(200)
        self.assertTrue(self.ledger.chain_state()["scanning"])
        self.assertEqual(self.ledger.chain_state()["rescanBlock"], 16)
        self.chain.tip_suffix = "second-branch"
        self.chain.canonical(1, "wrong", "second-branch-first-block")
        self.reconcile(200)
        self.assertIsNotNone(self.ledger.halted())
        self.assertEqual(self.chain.generated, [])

    def test_bounded_public_query_aborts_and_connection_remains_usable(self):
        with self.assertRaises(PublicQueryLimit):
            with query_budget(self.ledger.db, steps=10_000):
                self.ledger.db.execute("""WITH RECURSIVE x(n) AS
                    (SELECT 0 UNION ALL SELECT n+1 FROM x WHERE n<1000000) SELECT sum(n) FROM x""").fetchone()
        self.assertEqual(self.ledger.db.execute("SELECT 1").fetchone()[0], 1)
        self.found()
        message = self.ledger.blocks()[0]["msg"]
        self.assertTrue(self.ledger.share_exists(message, "a"))
        self.assertFalse(self.ledger.share_exists(message, "missing"))


if __name__ == "__main__":
    unittest.main()
