import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location("dependency_audit", Path(__file__).with_name("dependency-audit.py"))
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


class DependencyAuditTests(unittest.TestCase):
    def fixture(self, root, unknown=False, missing=False):
        jar = root / "example-1.0.jar"
        if not missing:
            jar.write_bytes(b"resolved artifact fixture")
        metadata = {"configurations": [{"configuration": {"name": "runtime"}, "modules": [
            {"module": {"organization": "org.example", "name": "example", "revision": "1.0"},
             "artifacts": [] if unknown else [[{"type": "jar"}, jar.as_uri()]],
             "licenses": [["Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"]], "evicted": False},
            {"module": {"organization": "org.example", "name": "example", "revision": "0.9"},
             "artifacts": [[{"type": "jar"}, jar.as_uri()]], "evicted": True}]}]}
        update, classpath = root / "update.json", root / "classpath.txt"
        update.write_text(json.dumps(metadata))
        classpath.write_text(str(jar))
        return update, classpath

    def test_actual_bytes_coordinates_licenses_and_no_private_paths(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            update, classpath = self.fixture(root)
            inventory = audit.inventory(update, classpath)
            self.assertEqual(inventory["identifiedJarCount"], 1)
            artifact = inventory["artifacts"][0]
            self.assertEqual(artifact["version"], "1.0")
            self.assertEqual(artifact["sha256"], audit.sha256(root / "example-1.0.jar"))
            sbom = audit.cyclonedx(inventory)
            self.assertEqual(sbom["specVersion"], "1.6")
            self.assertEqual(sbom["components"][0]["purl"], "pkg:maven/org.example/example@1.0")
            self.assertNotIn(str(root), json.dumps({"inventory": inventory, "sbom": sbom}))

    def test_unidentified_artifacts_are_explicit(self):
        with tempfile.TemporaryDirectory() as folder:
            inventory = audit.inventory(*self.fixture(Path(folder), unknown=True))
            self.assertEqual(inventory["identifiedJarCount"], 0)
            self.assertEqual(len(inventory["unidentifiedJars"]), 1)

    def test_missing_artifact_and_nonlocal_uri_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                audit.inventory(*self.fixture(Path(folder), missing=True))
        for uri in ("https://example.com/artifact.jar", "file://foreign-host/artifact.jar"):
            with self.assertRaises(ValueError):
                audit.file_uri(uri)

    def test_online_sends_only_coordinates_and_obeys_pagination(self):
        artifact = {"group": "org.example", "name": "example", "version": "1.0"}
        calls = []
        def fetch(url, payload=None):
            calls.append((url, payload))
            if url.endswith("querybatch"):
                query = payload["queries"][0]
                self.assertEqual(set(query), {"package", "version"} if len(calls) == 1 else
                                 {"package", "version", "page_token"})
                if query.get("page_token"):
                    return {"results": [{"vulns": [{"id": "GHSA-fixture"}]}]}
                return {"results": [{"next_page_token": "second-page"}]}
            return {"id": "GHSA-fixture", "summary": "Fixture advisory", "database_specific": {"severity": "HIGH"},
                    "affected": [{"package": {"ecosystem": "Maven", "name": "org.example:example"},
                                  "ranges": [{"events": [{"introduced": "0"}, {"fixed": "1.1"}]}]}]}
        packages, findings, _ = audit.osv_queries([artifact], fetch)
        self.assertEqual(len(packages), 1)
        self.assertEqual(findings[0]["fixedVersionsInRecord"], ["1.1"])
        self.assertEqual(len(calls), 3)

    def test_response_mismatch_or_error_cannot_claim_complete(self):
        artifact = {"group": "org.example", "name": "example", "version": "1.0"}
        for response in ({"results": []}, {"results": [{"error": "unknown"}]}):
            with self.assertRaises(ValueError):
                audit.osv_queries([artifact], lambda *_: response)

    def test_malicious_advisory_identifier_never_forms_a_request(self):
        artifact = {"group": "org.example", "name": "example", "version": "1.0"}
        with self.assertRaises(ValueError):
            audit.osv_queries([artifact], lambda *_: {"results": [{"vulns": [{"id": "../../foreign"}]}]})

    def test_withdrawn_records_are_not_actionable(self):
        artifact = {"group": "org.example", "name": "example", "version": "1.0"}
        def fetch(url, payload=None):
            if payload:
                return {"results": [{"vulns": [{"id": "GHSA-withdrawn"}]}]}
            return {"id": "GHSA-withdrawn", "withdrawn": "2026-01-01T00:00:00Z"}
        self.assertEqual(audit.osv_queries([artifact], fetch)[1], [])

    def test_offline_never_queries_the_network_and_exit_status_distinguishes_incomplete(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            update, classpath = self.fixture(root)
            with mock.patch.object(audit, "osv_queries", side_effect=AssertionError("Offline network call")):
                self.assertEqual(audit.main(["--update", str(update), "--classpath", str(classpath),
                                             "--output", str(root / "output")]), 0)
            report = json.loads((root / "output/report.json").read_text())
            self.assertFalse(report["advisoryQueryComplete"])
            with mock.patch.object(audit, "osv_queries", side_effect=ValueError("Invalid response")):
                self.assertEqual(audit.main(["--update", str(update), "--classpath", str(classpath),
                                             "--output", str(root / "output"), "--online"]), 2)


if __name__ == "__main__":
    unittest.main()
