#!/usr/bin/env python3
"""Package the native Zyrex desktop application with its own Java 11 runtime."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parent.parent
MAIN_CLASS = "org.zyrexchain.desktop.DesktopApp"
WINDOWS_UPGRADE_ID = "e32dbad8-4673-4c11-a475-c90a5a5962ad"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def native_platform():
    name = platform.system().lower()
    if name not in ("linux", "windows") or platform.machine().lower() not in ("amd64", "x86_64"):
        raise ValueError("Desktop packaging requires a native Linux or Windows x86_64 host")
    return name


def run(command, timeout=600, environment=None):
    subprocess.run([str(value) for value in command], check=True, timeout=timeout, env=environment)


def regular_file(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"Expected a regular packaging input: {path.name}")
    return path


def stage_inputs(source, target):
    """Copy only known application payloads, never an arbitrary build directory."""
    target.mkdir()
    for name in ("desktop.jar", "zyrex.jar"):
        original = regular_file(source / name)
        with zipfile.ZipFile(original) as archive:
            required = MAIN_CLASS.replace(".", "/") + ".class" if name == "desktop.jar" else "org/zyrexchain/ZyrexApp.class"
            if required not in archive.namelist():
                raise ValueError(f"{name} does not contain its expected application entry point")
        shutil.copyfile(original, target / name)
    shutil.copyfile(regular_file(ROOT / "LICENSE"), target / "LICENSE")
    licenses = source / "licenses"
    if licenses.exists():
        if licenses.is_symlink() or not licenses.is_dir():
            raise ValueError("The dependency license directory must not be a symbolic link")
        for original in sorted(licenses.rglob("*")):
            if original.is_symlink():
                raise ValueError("Dependency license files must not be symbolic links")
            if not original.is_file():
                continue
            if original.suffix.lower() not in (".txt", ".md", "", ".html"):
                raise ValueError(f"Unexpected dependency license file: {original.name}")
            destination = target / "licenses" / original.relative_to(licenses)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original, destination)


def check_runtime(runtime, operating_system):
    java = runtime / "bin" / ("java.exe" if operating_system == "windows" else "java")
    regular_file(java)
    regular_file(runtime / "lib" / "modules")
    if not (runtime / "legal").is_dir():
        raise ValueError("The Java runtime must retain its original legal notices")
    for item in runtime.rglob("*"):
        if item.is_symlink() and not item.resolve().is_relative_to(runtime):
            raise ValueError("The runtime contains a symbolic link outside its directory")
    result = subprocess.run([str(java), "-XshowSettings:properties", "-version"],
                            capture_output=True, text=True, check=True, timeout=20)
    properties = result.stdout + result.stderr
    if not re.search(r"java\.version\s*=\s*11\.", properties):
        raise ValueError("Bundle the tested Java 11 runtime")
    if not re.search(r"os\.arch\s*=\s*(amd64|x86_64)\b", properties):
        raise ValueError("The bundled runtime must be x86_64")


def verify_image(image, operating_system):
    application = image / ("app" if operating_system == "windows" else "lib/app")
    runtime = image / ("runtime" if operating_system == "windows" else "lib/runtime")
    launcher = image / ("Zyrex.exe" if operating_system == "windows" else "bin/Zyrex")
    regular_file(launcher)
    regular_file(application / "desktop.jar")
    regular_file(application / "zyrex.jar")
    regular_file(application / "LICENSE")
    regular_file(runtime / "bin" / ("java.exe" if operating_system == "windows" else "java"))
    configuration = (application / "Zyrex.cfg").read_text(encoding="utf-8")
    if MAIN_CLASS not in configuration or "desktop.jar" not in configuration or "zyrex.jar" not in configuration:
        raise ValueError("The generated launcher must include both application JARs on its class path")
    return launcher


def portable_archive(image, destination, operating_system):
    if operating_system == "windows":
        with zipfile.ZipFile(destination, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for path in sorted(image.rglob("*")):
                if path.is_symlink():
                    raise ValueError("Windows application images must not contain symbolic links")
                if path.is_file():
                    archive.write(path, Path("Zyrex") / path.relative_to(image))
    else:
        def public_owner(info):
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            return info

        with tarfile.open(destination, "x:gz") as archive:
            archive.add(image, arcname="Zyrex", filter=public_owner)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Directory containing desktop.jar and zyrex.jar")
    parser.add_argument("--runtime", type=Path, required=True, help="Unmodified native Java 11 runtime directory")
    parser.add_argument("--jpackage", default="jpackage", help="JDK 21 or newer native jpackage executable")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/desktop")
    parser.add_argument("--image-output", type=Path, help="Parent directory for the retained Zyrex application image")
    parser.add_argument("--platform", choices=("auto", "linux", "windows"), default="auto")
    parser.add_argument("--version", default="0.1.0", help="Three-component numeric application version")
    parser.add_argument("--icon", type=Path, required=True, help="Linux PNG or Windows ICO icon")
    args = parser.parse_args()
    host = native_platform()
    if args.platform not in ("auto", host):
        parser.error("Native installers must be built on their target operating system")
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        parser.error("--version must contain three numeric components")
    if any(int(number) > limit for number, limit in zip(args.version.split("."), (255, 255, 65535))):
        parser.error("--version exceeds Windows installer version limits")
    args.input = args.input.resolve(strict=True)
    args.runtime = args.runtime.resolve(strict=True)
    args.icon = args.icon.resolve(strict=True)
    if args.icon.suffix.lower() != (".ico" if host == "windows" else ".png"):
        parser.error("The icon format must match the native package platform")
    regular_file(args.icon)
    executable = shutil.which(args.jpackage)
    if executable is None:
        parser.error("jpackage is unavailable; install a native JDK 21 or newer")
    packager_version = subprocess.check_output([executable, "--version"], text=True, timeout=20).strip()
    if int(packager_version.split(".")[0]) < 21:
        parser.error("Use JDK 21 or newer for native desktop packaging")
    check_runtime(args.runtime, host)
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    image_parent = (args.image_output or ROOT / "desktop/target/package" / f"{host}-amd64").resolve()
    image_parent.mkdir(parents=True, exist_ok=True)
    image = image_parent / "Zyrex"
    basename = f"zyrex-desktop-{args.version}-testnet-{host}-amd64"
    archive = args.output / (basename + (".zip" if host == "windows" else ".tar.gz"))
    installer = args.output / (basename + (".exe" if host == "windows" else ".deb"))
    sums = args.output / f"SHA256SUMS-{host}-amd64"
    for path in (image, archive, installer, sums):
        if path.exists():
            parser.error(f"Refusing to replace an existing packaging output: {path.name}")
    common = [executable, "--name", "Zyrex", "--app-version", args.version, "--vendor", "Zyrex",
              "--description", "Zyrex public testnet wallet and full node"]
    with tempfile.TemporaryDirectory(prefix="zyrex-package-") as temporary:
        temporary = Path(temporary)
        staged = temporary / "input"
        stage_inputs(args.input, staged)
        run(common + ["--type", "app-image", "--input", staged, "--main-jar", "desktop.jar",
                      "--main-class", MAIN_CLASS, "--runtime-image", args.runtime, "--dest", image_parent,
                      "--icon", args.icon, "--java-options", "-Xms32m", "--java-options", "-Xmx256m",
                      "--java-options", "-Dfile.encoding=UTF-8"])
        launcher = verify_image(image, host)
        installer_directory = temporary / "installer"
        installer_directory.mkdir()
        options = ["--app-image", image, "--dest", installer_directory, "--license-file", ROOT / "LICENSE",
                   "--about-url", "https://zyrexchain.com"]
        if host == "windows":
            options += ["--type", "exe", "--win-per-user-install", "--win-dir-chooser", "--win-menu",
                        "--win-menu-group", "Zyrex", "--win-shortcut", "--win-upgrade-uuid", WINDOWS_UPGRADE_ID]
        else:
            options += ["--type", "deb", "--linux-package-name", "zyrex-desktop", "--linux-shortcut",
                        "--linux-menu-group", "Finance", "--linux-app-category", "net",
                        "--linux-package-deps", "fontconfig, fonts-dejavu-core",
                        "--linux-deb-maintainer", "339716451+zyrexchain@users.noreply.github.com"]
        environment = None
        if host == "linux" and os.geteuid() == 0:
            # A root build already has the ownership privileges dpkg-deb needs.
            # Avoid a redundant fakeroot IPC server, without changing host tools.
            actual_fakeroot = shutil.which("fakeroot")
            if actual_fakeroot is None:
                raise RuntimeError("Linux installer packaging requires the fakeroot package")
            tools = temporary / "tools"
            tools.mkdir()
            wrapper = tools / "fakeroot"
            wrapper.write_text("#!/bin/sh\ncase \"$1\" in\n"
                               f"  -v|--version) exec {shlex.quote(actual_fakeroot)} \"$@\" ;;\n"
                               "  *) exec \"$@\" ;;\nesac\n", encoding="utf-8")
            wrapper.chmod(0o755)
            environment = dict(os.environ)
            environment["PATH"] = str(tools) + os.pathsep + environment.get("PATH", "")
        run(common + options, environment=environment)
        built = list(installer_directory.glob("*.exe" if host == "windows" else "*.deb"))
        if len(built) != 1:
            raise RuntimeError("jpackage did not produce exactly one native installer")
        shutil.copyfile(built[0], installer)
        portable_archive(image, archive, host)
    with sums.open("x", encoding="utf-8", newline="\n") as output:
        for path in sorted((archive, installer)):
            output.write(f"{sha256(path)}  {path.name}\n")
    print(json.dumps({"platform": host, "applicationImage": str(image), "launcher": str(launcher),
                      "assets": [str(archive), str(installer), str(sums)]}, indent=2))


if __name__ == "__main__":
    main()
