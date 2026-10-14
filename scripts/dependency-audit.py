#!/usr/bin/env python3
"""Inventory resolved SBT runtime JARs and optionally query public OSV Maven records.

Only public Maven coordinates and versions leave this machine. Source, JAR bytes,
classpath paths and configuration are never sent. Generated files contain no paths.
"""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


OSV_BASE = "https://api.osv.dev/v1"
MAX_RESPONSE = 8 * 1024 * 1024
MAX_JARS = 2048
MAX_ADVISORIES = 256
MAX_PAGES = 20
COORDINATE = re.compile(r"^[A-Za-z0-9_.+:-]{1,200}$")
ADVISORY_ID = re.compile(r"^[A-Za-z0-9_.-]{1,150}$")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_uri(value):
    parsed = urllib.parse.urlparse(value)
    if parsed.scheme != "file" or parsed.netloc not in ("", "localhost"):
        raise ValueError("SBT artifact must be a local file URI")
    return Path(urllib.parse.unquote(parsed.path)).resolve()


def coordinate(module):
    result = tuple(module[key] for key in ("organization", "name", "revision"))
    if not all(isinstance(value, str) and COORDINATE.fullmatch(value) for value in result):
        raise ValueError("Invalid Maven coordinate in resolved metadata")
    return result


def inventory(update_path, classpath_path, configuration="runtime"):
    """The actual external classpath, not declared or evicted versions, selects bytes."""
    update = json.loads(update_path.read_text())
    raw_paths = classpath_path.read_text().strip().split(os.pathsep)
    paths = sorted({Path(value).resolve() for value in raw_paths if value})
    if not paths or len(paths) > MAX_JARS:
        raise ValueError("Empty or excessive external JAR classpath")
    selected = next((c for c in update["configurations"]
                     if c["configuration"]["name"] == configuration), None)
    if selected is None:
        raise ValueError("Resolved configuration is missing")
    records = {}
    for module in selected["modules"]:
        if module.get("evicted", False):
            continue
        coords = coordinate(module["module"])
        for artifact, location in module.get("artifacts", []):
            path = file_uri(location)
            record = (coords, artifact.get("classifier"), module.get("licenses", []))
            previous = records.get(path)
            if previous and previous[:2] != record[:2]:
                raise ValueError("Conflicting coordinates identify the same runtime JAR")
            records[path] = record
    artifacts = []
    unidentified = []
    for path in paths:
        if not path.is_file() or path.suffix != ".jar":
            raise ValueError("Runtime external classpath contains a missing or non-JAR artifact")
        common = {"filename": path.name, "sha256": sha256(path), "sizeBytes": path.stat().st_size}
        record = records.get(path)
        if record is None:
            unidentified.append(common)
            continue
        (group, name, version), classifier, licenses = record
        purl = "pkg:maven/{}/{}@{}".format(*[urllib.parse.quote(v, safe="._-") for v in (group, name, version)])
        if classifier:
            purl += "?classifier=" + urllib.parse.quote(classifier, safe="._-")
        artifacts.append({**common, "group": group, "name": name, "version": version,
                          "classifier": classifier, "purl": purl, "licenses": licenses})
    refs = [item["purl"] for item in artifacts]
    if len(set(refs)) != len(refs):
        raise ValueError("Duplicate package identifiers require explicit artifact disambiguation")
    return {"configuration": configuration, "classpathJarCount": len(paths),
            "identifiedJarCount": len(artifacts), "unidentifiedJars": unidentified,
            "licensesUnknown": [item["purl"] for item in artifacts if not item["licenses"]],
            "updateMetadataSha256": sha256(update_path), "classpathExportSha256": sha256(classpath_path),
            "artifacts": artifacts}


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def cyclonedx(data, artifact=None):
    components = []
    for item in data["artifacts"]:
        component = {"type": "library", "bom-ref": item["purl"], "group": item["group"],
                     "name": item["name"], "version": item["version"], "purl": item["purl"],
                     "hashes": [{"alg": "SHA-256", "content": item["sha256"]}],
                     "properties": [{"name": "zyrex:artifact-filename", "value": item["filename"]},
                                    {"name": "zyrex:artifact-size-bytes", "value": str(item["sizeBytes"])}]}
        licenses = []
        for name, url in item["licenses"]:
            license_value = {"name": name}
            if isinstance(url, str) and url.startswith(("https://", "http://")):
                license_value["url"] = url
            licenses.append({"license": license_value})
        if licenses:
            component["licenses"] = licenses
        components.append(component)
    application = {"type": "application", "bom-ref": "zyrex-node", "name": "Zyrex node"}
    if artifact is not None:
        application["hashes"] = [{"alg": "SHA-256", "content": sha256(artifact)}]
    properties = [
        {"name": "zyrex:inventory-scope", "value": "resolved-external-runtime-jars"},
        {"name": "zyrex:classpath-jar-count", "value": str(data["classpathJarCount"])},
        {"name": "zyrex:unidentified-jar-count", "value": str(len(data["unidentifiedJars"]))},
        {"name": "zyrex:update-metadata-sha256", "value": data["updateMetadataSha256"]},
        {"name": "zyrex:classpath-export-sha256", "value": data["classpathExportSha256"]},
        {"name": "zyrex:artifact-link", "value": "supplied-hash-only; source-and-assembly-provenance-not-verified"},
    ]
    # Runtime classpath membership is known. Transitive dependency edges are not inferred.
    return {"$schema": "http://cyclonedx.org/schema/bom-1.6.schema.json", "bomFormat": "CycloneDX",
            "specVersion": "1.6", "serialNumber": "urn:uuid:" + str(uuid.uuid4()), "version": 1,
            "metadata": {"timestamp": timestamp(), "component": application, "properties": properties},
            "components": components,
            "dependencies": [{"ref": "zyrex-node", "dependsOn": [c["bom-ref"] for c in components]}]}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("OSV API redirects are refused")


def request_json(endpoint, payload=None):
    """Fixed HTTPS authority, bounded response, no proxies or redirect authority changes."""
    if not endpoint.startswith(OSV_BASE + "/"):
        raise ValueError("Untrusted audit endpoint")
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(endpoint, data=body,
                                    headers={"Content-Type": "application/json", "User-Agent": "ZyrexDependencyAudit/1"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(request, timeout=20) as response:
        encoded = response.read(MAX_RESPONSE + 1)
    if len(encoded) > MAX_RESPONSE:
        raise ValueError("OSV API response exceeds the size limit")
    result = json.loads(encoded)
    if not isinstance(result, dict):
        raise ValueError("Malformed OSV API response")
    return result


def osv_queries(artifacts, fetch=request_json):
    packages = sorted({(a["group"], a["name"], a["version"]) for a in artifacts})
    found = {coords: set() for coords in packages}
    pending = [(coords, None) for coords in packages]
    pages = 0
    while pending:
        pages += 1
        if pages > MAX_PAGES:
            raise ValueError("OSV pagination limit reached; audit is incomplete")
        next_pending = []
        for start in range(0, len(pending), 100):
            chunk = pending[start:start + 100]
            queries = []
            for (group, name, version), token in chunk:
                query = {"package": {"ecosystem": "Maven", "name": group + ":" + name}, "version": version}
                if token:
                    query["page_token"] = token
                queries.append(query)
            results = fetch(OSV_BASE + "/querybatch", {"queries": queries}).get("results")
            if not isinstance(results, list) or len(results) != len(chunk):
                raise ValueError("OSV batch response does not match its package requests")
            for (coords, _), result in zip(chunk, results):
                if not isinstance(result, dict) or "error" in result:
                    raise ValueError("OSV returned an incomplete package result")
                for vuln in result.get("vulns", []):
                    identifier = vuln.get("id", "")
                    if not ADVISORY_ID.fullmatch(identifier):
                        raise ValueError("Invalid OSV advisory identifier")
                    found[coords].add(identifier)
                token = result.get("next_page_token")
                if token:
                    if not isinstance(token, str) or len(token) > 4096:
                        raise ValueError("Invalid OSV pagination token")
                    next_pending.append((coords, token))
        pending = next_pending
    identifiers = sorted({identifier for values in found.values() for identifier in values})
    if len(identifiers) > MAX_ADVISORIES:
        raise ValueError("Too many advisory details; audit is incomplete")
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        details = dict(executor.map(lambda identifier: (identifier, fetch(OSV_BASE + "/vulns/" + identifier)), identifiers))
    findings = []
    for (group, name, version), values in found.items():
        for identifier in sorted(values):
            detail = details[identifier]
            if detail.get("id") != identifier:
                raise ValueError("OSV advisory detail does not match its identifier")
            if detail.get("withdrawn"):
                continue
            fixed = sorted({event["fixed"] for affected in detail.get("affected", [])
                            if affected.get("package", {}).get("ecosystem") == "Maven"
                            and affected.get("package", {}).get("name") == group + ":" + name
                            for interval in affected.get("ranges", []) for event in interval.get("events", [])
                            if "fixed" in event})
            findings.append({"group": group, "name": name, "version": version, "id": identifier,
                             "aliases": detail.get("aliases", []), "summary": detail.get("summary", ""),
                             "severity": detail.get("database_specific", {}).get("severity", "UNKNOWN"),
                             "fixedVersionsInRecord": fixed,
                             "primaryRecord": "https://osv.dev/vulnerability/" + identifier,
                             "references": detail.get("references", [])})
    return packages, findings, details


def write_json(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(content, stream, indent=2, sort_keys=True)
        stream.write("\n")
    temporary.replace(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update", type=Path, default=Path("target/scala-2.12/update/update_cache_2.12/output"))
    parser.add_argument("--classpath", type=Path,
                        default=Path("target/streams/runtime/externalDependencyClasspath/_global/streams/export"))
    parser.add_argument("--configuration", default="runtime")
    parser.add_argument("--output", type=Path, default=Path(".local/dependency-audit"))
    parser.add_argument("--artifact", type=Path, help="Optional native JAR hash; does not prove assembly/source provenance")
    parser.add_argument("--online", action="store_true", help="Send public Maven coordinates to the OSV API")
    args = parser.parse_args(argv)
    try:
        data = inventory(args.update, args.classpath, args.configuration)
        if args.artifact is not None and (not args.artifact.is_file() or args.artifact.suffix != ".jar"):
            raise ValueError("Native artifact is not a JAR file")
        write_json(args.output / "sbom.cdx.json", cyclonedx(data, args.artifact))
        report = {"schemaVersion": 1, "checkedAt": timestamp(), "inventory": data,
                  "online": args.online, "advisoryQueryComplete": False, "findings": [],
                  "scopeLimits": ["External resolved JARs only; excludes application code, JDK, OS and native binaries.",
                                  "OSV coverage is incomplete; no results do not prove absence of vulnerabilities.",
                                  "Version matches require reachability review; fixed versions are advisory data, not an upgrade plan.",
                                  "Cached SBT resolution does not prove a supplied assembly was built from these inputs."]}
        exit_code = 0
        if args.online:
            try:
                packages, findings, details = osv_queries(data["artifacts"])
                report.update({"queriedPackages": len(packages), "findings": findings, "advisoryQueryComplete": True})
                write_json(args.output / "osv-records.json", details)
                if findings:
                    exit_code = 1
            except (ValueError, OSError, urllib.error.URLError, KeyError, TypeError):
                report["queryError"] = "OSV request or response validation failed; audit is incomplete."
                exit_code = 2
        if data["unidentifiedJars"]:
            exit_code = 2
        write_json(args.output / "report.json", report)
        print(json.dumps({"identifiedJars": data["identifiedJarCount"], "unidentifiedJars": len(data["unidentifiedJars"]),
                          "licenseMetadataMissing": len(data["licensesUnknown"]), "online": args.online,
                          "advisoryQueryComplete": report["advisoryQueryComplete"], "findings": len(report["findings"]),
                          "exitCode": exit_code}))
        return exit_code
    except (ValueError, OSError, KeyError, TypeError):
        print("Dependency inventory failed: refresh SBT resolution and runtime classpath exports.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
