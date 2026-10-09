#!/usr/bin/env python3
"""Fetch and validate a public checkpoint with verification source and native binary pins."""
import argparse
import json
import ssl
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from bootstrap_validation import checkpoint, height_pin, sha256_file, source_pin


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, stream, code, message, headers, new_url):
        raise ValueError("Checkpoint redirects are not allowed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--jar", type=Path, default=Path("target/scala-2.12/zyrex.jar"))
    parser.add_argument("--source-commit")
    args = parser.parse_args()
    source, dirty = source_pin(args.source_commit)
    jar_hash = sha256_file(args.jar)
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), NoRedirect(),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()))

    def fetch(path):
        request = urllib.request.Request("https://explorer.zyrexchain.com" + path,
                                         headers={"User-Agent": "Mozilla/5.0 Zyrex-bootstrap-check"})
        with opener.open(request, timeout=15) as response:
            data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024:
            raise ValueError("Checkpoint response is too large")
        return json.loads(data)

    status = fetch("/api/status")
    height = min(height_pin(status.get("indexedHeight")), height_pin(status.get("node", {}).get("fullHeight")))
    expected = checkpoint(status, fetch("/api/block/" + str(height)))
    expected.update({"checkpointSource": "https://explorer.zyrexchain.com",
                     "fetchedAtUtc": datetime.now(timezone.utc).isoformat(),
                     "verificationSourceCommit": source, "verificationJarSha256": jar_hash,
                     "verificationSourceTreeDirty": dirty})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(expected, indent=2) + "\n")
    print(json.dumps(expected, indent=2))


if __name__ == "__main__":
    main()
