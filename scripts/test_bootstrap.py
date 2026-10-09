"""Native bootstrap checkpoint, provenance, restart and failure-report regressions."""
import importlib.util
import io
import json
import socket
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from bootstrap_validation import GENESIS, checkpoint, epoch_evidence, ingress_evidence, source_pin, validate_expected

SPEC = importlib.util.spec_from_file_location("bootstrap_smoke", Path(__file__).with_name("bootstrap-smoke.py"))
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)
BLOCK = "a" * 64
ROOT = "b" * 66
COMMIT = "c" * 40
JAR_HASH = "d" * 64


def reference():
    return {"network": "testnet", "genesisBlockId": GENESIS, "fullHeight": 91,
            "headersHeight": 91, "bestFullHeaderId": BLOCK, "stateRoot": ROOT}


def native_info():
    return dict(reference(), isMining=False, stateType="utxo")


def full_block():
    return {"header": {"id": BLOCK, "height": 91, "stateRoot": ROOT},
            "blockTransactions": {"headerId": BLOCK, "transactions": [{"id": "tx"}]}}


class CheckpointTests(unittest.TestCase):
    def test_foreign_genesis_malformed_hashes_and_noninteger_height_fail(self):
        self.assertEqual(validate_expected(reference()), reference())
        mutations = ({"genesisBlockId": "e" * 64}, {"network": "mainnet"}, {"fullHeight": True},
                     {"fullHeight": -1}, {"fullHeight": 1 << 31}, {"headersHeight": 90},
                     {"headersHeight": True}, {"bestFullHeaderId": "A" * 64}, {"stateRoot": "b" * 64},
                     {"verificationSourceCommit": "short"}, {"verificationJarSha256": "f" * 63},
                     {"verificationSourceTreeDirty": "false"}, {"chainRules": {"daaEpochLength": 2048}})
        for fields in mutations:
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                validate_expected(dict(reference(), **fields))

    def test_checkpoint_requires_consistent_full_block_and_current_source_identity(self):
        status = {"network": "testnet", "genesisId": GENESIS, "ready": True, "indexedHeight": 91, "node": native_info()}
        result = checkpoint(status, full_block())
        self.assertEqual(result["fullHeight"], 91)
        self.assertEqual(result["chainRules"], {"daaEpochLength": 64, "votingEpochLength": 128})
        for bad in ({"ready": False}, {"ready": 1}, {"network": "mainnet"}, {"genesisId": "f" * 64},
                    {"indexedHeight": True}, {"node": dict(native_info(), headersHeight=90)},
                    {"node": dict(native_info(), bestFullHeaderId="f" * 64)},
                    {"node": dict(native_info(), stateRoot="f" * 66)}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                checkpoint(dict(status, **bad), full_block())
        for transactions in ({"headerId": "f" * 64, "transactions": [{}]},
                             {"headerId": BLOCK, "transactions": []}, {"headerId": BLOCK, "transactions": "invalid"}):
            with self.subTest(transactions=transactions), self.assertRaises(ValueError):
                checkpoint(status, dict(full_block(), blockTransactions=transactions))

    def test_epoch_evidence_distinguishes_data_epoch_end_from_applied_boundary(self):
        early = epoch_evidence(91)
        self.assertEqual(early["executedDaaBoundaryTransitions"], 1)
        self.assertEqual(early["executedVotingBoundaryTransitions"], 0)
        self.assertFalse(early["multipleEpochHistoryVerified"])
        before = epoch_evidence(384)
        self.assertEqual(before["completeDaaDataEpochs"], 6)
        self.assertEqual(before["executedDaaBoundaryTransitions"], 5)
        self.assertFalse(before["multipleEpochHistoryVerified"])
        after = epoch_evidence(385)
        self.assertEqual(after["executedDaaBoundaryTransitions"], 6)
        self.assertEqual(after["executedVotingBoundaryTransitions"], 3)
        self.assertTrue(after["multipleEpochHistoryVerified"])
        self.assertFalse(after["daaAdaptationStressVerified"])

    def test_same_or_different_dns_does_not_claim_operator_independence(self):
        common = ingress_evidence([{"dnsAddresses": ["203.0.113.7"]}, {"dnsAddresses": ["203.0.113.7"]}])
        self.assertEqual(common["commonDnsAddresses"], ["203.0.113.7"])
        self.assertFalse(common["independentIngress"])
        diverse = ingress_evidence([{"dnsAddresses": ["203.0.113.7"]}, {"dnsAddresses": ["203.0.113.8"]}])
        self.assertFalse(diverse["independentInfrastructureVerified"])

    def test_full_source_commit_must_equal_checked_out_head(self):
        with patch("bootstrap_validation.subprocess.check_output", side_effect=[COMMIT, ""]):
            self.assertEqual(source_pin(COMMIT), (COMMIT, False))
        with patch("bootstrap_validation.subprocess.check_output", return_value=COMMIT), self.assertRaises(ValueError):
            source_pin("f" * 40)
        with patch("bootstrap_validation.subprocess.check_output", side_effect=[COMMIT, "?? new-code.py"]):
            self.assertEqual(source_pin(COMMIT), (COMMIT, True))


class NativeObservationTests(unittest.TestCase):
    def test_native_applied_reference_checks_canonical_block_and_state_root(self):
        responses = {"/info": native_info(), "/blocks/at/1": [GENESIS], "/blocks/at/91": [BLOCK],
                     "/blocks/" + BLOCK: full_block()}
        with patch.object(SMOKE, "api", side_effect=lambda _port, path: responses[path]):
            self.assertEqual(SMOKE.applied_baseline(12345, reference())["fullHeight"], 91)
            responses["/blocks/at/91"] = ["e" * 64]
            with self.assertRaisesRegex(RuntimeError, "Canonical block"):
                SMOKE.applied_baseline(12345, reference())
            responses["/blocks/at/91"] = [BLOCK]
            responses["/blocks/" + BLOCK]["header"]["stateRoot"] = "e" * 66
            with self.assertRaisesRegex(RuntimeError, "state root"):
                SMOKE.applied_baseline(12345, reference())

    def test_mining_enabled_or_foreign_applied_genesis_is_rejected(self):
        with patch.object(SMOKE, "api", return_value=dict(native_info(), isMining=True)):
            with self.assertRaisesRegex(RuntimeError, "mining disabled"):
                SMOKE.applied_baseline(12345, reference())
        with patch.object(SMOKE, "api", side_effect=[native_info(), ["e" * 64]]):
            with self.assertRaisesRegex(RuntimeError, "genesis"):
                SMOKE.applied_baseline(12345, reference())

    def test_owned_failed_peer_really_accepts_and_closes_local_transport(self):
        failed = SMOKE.FailedPeer()
        try:
            with socket.create_connection(("127.0.0.1", failed.port), timeout=1) as connection:
                self.assertEqual(connection.recv(1), b"")
            self.assertGreaterEqual(failed.accepted, 1)
        finally:
            failed.close()
        self.assertFalse(failed.thread.is_alive())

    def test_restart_config_has_one_chosen_peer_and_fallback_adds_only_owned_peer(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            for fallback in (None, 22222):
                run = base / ("normal" if fallback is None else "fallback")
                run.mkdir()
                config, key = SMOKE.write_config(run, 19533, 12345, 12346, fallback)
                source = config.read_text()
                self.assertIn('"zyrexchain.com:19533"', source)
                self.assertNotIn("zyrexchain.com:19531", source)
                self.assertIn("scorex.network.peerDiscovery = false", source)
                self.assertIn("zyrex.node.mining = false", source)
                self.assertNotIn(key, source)
                self.assertEqual(config.stat().st_mode & 0o077, 0)
                if fallback:
                    self.assertIn('"127.0.0.1:22222"', source)
                    self.assertIn("scorex.network.maxConnections = 2", source)
                else:
                    self.assertIn("scorex.network.maxConnections = 1", source)

    def test_binary_pin_mismatch_and_minimum_height_produce_failed_artifact_without_starting_node(self):
        for fields, extra in (({"verificationJarSha256": "e" * 64}, []), ({}, ["--min-height", "385"])):
            with self.subTest(fields=fields), tempfile.TemporaryDirectory() as temporary:
                run = Path(temporary)
                jar = run / "native.jar"
                jar.write_bytes(b"test-only binary")
                expected = run / "checkpoint.json"
                expected.write_text(json.dumps(dict(reference(), **fields)))
                output = run / "report.json"
                argv = ["bootstrap-smoke.py", "--jar", str(jar), "--java", sys.executable,
                        "--expected-info", str(expected), "--output", str(output), *extra]
                with patch.object(sys, "argv", argv), patch.object(SMOKE, "source_pin", return_value=(COMMIT, True)), \
                        patch.object(SMOKE, "sha256_file", return_value=JAR_HASH), \
                        patch.object(SMOKE, "verify_port", side_effect=AssertionError("Must not start a native process")), \
                        patch("sys.stdout", new_callable=io.StringIO), self.assertRaises(SystemExit) as stopped:
                    SMOKE.main()
                self.assertEqual(stopped.exception.code, 1)
                report = json.loads(output.read_text())
                self.assertFalse(report["success"])
                self.assertEqual(report["checks"], [])
                self.assertEqual(report["sourceCommit"], COMMIT)
                self.assertEqual(report["jarSha256"], JAR_HASH)
                self.assertFalse(report["sourceBinaryLinkVerified"])
                self.assertIn("error", report)


if __name__ == "__main__":
    unittest.main()
