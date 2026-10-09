#!/usr/bin/env python3
"""Download an official Temurin Java 11 runtime and verify its published SHA-256."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import ssl
import tarfile
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile


MAX_ARCHIVE = 512 * 1024 * 1024
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                   urllib.request.HTTPSHandler(context=ssl.create_default_context()))


def fetch(url, destination, maximum):
    request = urllib.request.Request(url, headers={"User-Agent": "Zyrex-desktop-build"})
    digest = hashlib.sha256()
    size = 0
    with OPENER.open(request, timeout=60) as response, destination.open("xb") as output:
        if urllib.parse.urlparse(response.geturl()).scheme != "https":
            raise ValueError("Runtime downloads must remain on HTTPS")
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            size += len(chunk)
            if size > maximum:
                raise ValueError("Runtime download exceeded the supported size")
            digest.update(chunk)
            output.write(chunk)
    return digest.hexdigest()


def extract(archive, destination, operating_system):
    if operating_system == "windows":
        with zipfile.ZipFile(archive) as source:
            for item in source.infolist():
                path = PurePosixPath(item.filename)
                if path.is_absolute() or ".." in path.parts or ":" in item.filename or "\\" in item.filename:
                    raise ValueError("Unsafe runtime archive path")
                if (item.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError("Windows runtime archives must not contain symbolic links")
            if sum(item.file_size for item in source.infolist()) > 1024 * 1024 * 1024:
                raise ValueError("Expanded runtime exceeded the supported size")
            source.extractall(destination)
    else:
        if not hasattr(tarfile, "data_filter"):
            raise ValueError("Runtime extraction requires a Python version with tarfile data filters")
        with tarfile.open(archive, "r:gz") as source:
            if sum(item.size for item in source.getmembers()) > 1024 * 1024 * 1024:
                raise ValueError("Expanded runtime exceeded the supported size")
            source.extractall(destination, filter="data")


def official_github_release(temporary, operating_system):
    """Use the same publisher's release API when its distribution API is unavailable."""
    metadata = temporary / "github-release.json"
    fetch("https://api.github.com/repos/adoptium/temurin11-binaries/releases/latest", metadata, 4 * 1024 * 1024)
    release = json.loads(metadata.read_text(encoding="utf-8"))
    extension = r"\.zip" if operating_system == "windows" else r"\.tar\.gz"
    pattern = rf"OpenJDK11U-jre_x64_{operating_system}_hotspot_11\.[0-9._]+{extension}"
    assets = release["assets"]
    matching = [asset for asset in assets if re.fullmatch(pattern, asset["name"])]
    if len(matching) != 1:
        raise ValueError("Expected exactly one matching official runtime release archive")
    archive = matching[0]
    checksums = [asset for asset in assets if asset["name"] == archive["name"] + ".sha256.txt"]
    if len(checksums) != 1:
        raise ValueError("The official runtime release is missing its checksum asset")
    checksum_file = temporary / "runtime.sha256.txt"
    fetch(checksums[0]["browser_download_url"], checksum_file, 4096)
    checksum = checksum_file.read_text(encoding="utf-8").strip().split()[0]
    published_digest = archive.get("digest")
    if published_digest and published_digest != "sha256:" + checksum:
        raise ValueError("The official checksum asset and release metadata disagree")
    return {"release_name": release["tag_name"], "binary": {
        "os": operating_system, "architecture": "x64", "image_type": "jre",
        "package": {"checksum": checksum, "link": archive["browser_download_url"]}}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("linux", "windows"), default=platform.system().lower())
    parser.add_argument("--output", type=Path, required=True, help="New directory for the extracted runtime")
    args = parser.parse_args()
    if args.platform not in ("linux", "windows"):
        parser.error("Choose a Linux or Windows runtime")
    args.output = args.output.resolve()
    if args.output.exists():
        parser.error("Refusing to replace an existing runtime directory")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    query = urllib.parse.urlencode({"architecture": "x64", "heap_size": "normal", "image_type": "jre",
                                    "os": args.platform, "vendor": "eclipse", "project": "jdk"})
    url = "https://api.adoptium.net/v3/assets/latest/11/hotspot?" + query
    with tempfile.TemporaryDirectory(prefix="zyrex-runtime-", dir=args.output.parent) as temporary:
        temporary = Path(temporary)
        metadata = temporary / "metadata.json"
        try:
            fetch(url, metadata, 4 * 1024 * 1024)
            releases = json.loads(metadata.read_text(encoding="utf-8"))
            if not isinstance(releases, list) or len(releases) != 1:
                raise ValueError("Expected exactly one matching Temurin Java 11 runtime")
            release = releases[0]
        except urllib.error.HTTPError as error:
            if error.code not in (403, 429, 500, 502, 503, 504):
                raise
            release = official_github_release(temporary, args.platform)
        binary = release["binary"]
        if (binary["os"], binary["architecture"], binary["image_type"]) != (args.platform, "x64", "jre"):
            raise ValueError("Runtime metadata does not match the selected target")
        package = binary["package"]
        checksum = package["checksum"]
        if not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise ValueError("The runtime does not have a valid SHA-256 checksum")
        parsed = urllib.parse.urlparse(package["link"])
        if parsed.scheme != "https" or parsed.netloc != "github.com" or not parsed.path.startswith(
                "/adoptium/temurin11-binaries/releases/download/"):
            raise ValueError("Runtime metadata points outside the official Temurin release repository")
        archive = temporary / ("runtime.zip" if args.platform == "windows" else "runtime.tar.gz")
        if fetch(package["link"], archive, MAX_ARCHIVE) != checksum:
            raise ValueError("Runtime archive SHA-256 verification failed")
        extracted = temporary / "extracted"
        extracted.mkdir()
        extract(archive, extracted, args.platform)
        roots = list(extracted.iterdir())
        if len(roots) != 1 or not roots[0].is_dir() or roots[0].is_symlink():
            raise ValueError("Unexpected runtime archive layout")
        runtime = roots[0]
        java = runtime / "bin" / ("java.exe" if args.platform == "windows" else "java")
        if not java.is_file() or not (runtime / "legal").is_dir() or not (runtime / "lib/modules").is_file():
            raise ValueError("Runtime archive lacks required binaries or legal notices")
        record = {"release": release["release_name"], "platform": args.platform, "architecture": "x64",
                  "sha256": checksum, "downloadUrl": package["link"]}
        (runtime / "zyrex-runtime-source.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        shutil.move(str(runtime), str(args.output))
    print(json.dumps({"runtime": str(args.output), **record}, indent=2))


if __name__ == "__main__":
    main()
