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
import xml.etree.ElementTree as ElementTree
import zipfile


ROOT = Path(__file__).resolve().parent.parent
MAIN_CLASS = "org.zyrexchain.desktop.DesktopApp"
NODE_CLASS = "org.zyrexchain.desktop.NodeBootstrap"
NODE_JAVA_OPTIONS = ("-Xms64m", "-Xmx1024m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8")
WINDOWS_UPGRADE_ID = "e32dbad8-4673-4c11-a475-c90a5a5962ad"
ASSEMBLY_NAMESPACE = "urn:schemas-microsoft-com:asm.v1"
APPLICATION_NAMESPACE = "urn:schemas-microsoft-com:asm.v3"
CODE_PAGE_NAMESPACE = "http://schemas.microsoft.com/SMI/2019/WindowsSettings"


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


def application_version(source, requested=None):
    with zipfile.ZipFile(regular_file(source / "desktop.jar")) as archive:
        try:
            manifest = archive.getinfo("META-INF/MANIFEST.MF")
        except KeyError as error:
            raise ValueError("The desktop application manifest is missing") from error
        if manifest.file_size > 65536:
            raise ValueError("The desktop application manifest is too large")
        main = archive.read(manifest).decode("utf-8").replace("\r\n", "\n").split("\n\n", 1)[0]
    versions = [line.removeprefix("Implementation-Version: ") for line in main.splitlines()
                if line.startswith("Implementation-Version: ")]
    numeric = r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    if len(versions) != 1 or not re.fullmatch(numeric + "-testnet", versions[0]):
        raise ValueError("The desktop manifest must declare one numeric testnet Implementation-Version")
    version = versions[0].removesuffix("-testnet")
    if any(int(number) > limit for number, limit in zip(version.split("."), (255, 255, 65535))):
        raise ValueError("The desktop application version exceeds Windows installer version limits")
    if requested is not None and requested != version:
        raise ValueError("--version must match the desktop application's manifest version")
    return versions[0], version


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
    if operating_system == "windows":
        regular_file(image / "ZyrexNode.exe")
        helper = regular_file(application / "ZyrexNode.cfg").read_text(encoding="utf-8")
        lines = [re.sub(r"/+", "/", line.replace("\\", "/")) for line in helper.splitlines()]
        expected = {"app.mainclass=" + NODE_CLASS, "app.classpath=$APPDIR/desktop.jar",
                    "app.classpath=$APPDIR/zyrex.jar", *("java-options=" + value for value in NODE_JAVA_OPTIONS)}
        if not expected.issubset(lines):
            raise ValueError("The internal node launcher must use its bundled JARs and bounded JVM settings")
        for option in ("-Xms", "-Xmx", "-XX:ActiveProcessorCount=", "-Dfile.encoding="):
            if sum(line.startswith("java-options=" + option) for line in lines) != 1:
                raise ValueError("The internal node launcher has conflicting JVM settings")
        metadata = ElementTree.parse(regular_file(application / ".jpackage.xml")).getroot()
        helpers = [item for item in metadata.findall("add-launcher") if item.get("name") == "ZyrexNode"]
        if len(helpers) != 1 or any(helpers[0].get(flag) != "false" for flag in ("shortcut", "menu", "service")):
            raise ValueError("The internal node launcher must not create shortcuts, menu entries or services")
    return launcher


def windows_node_launcher(temporary, staged):
    with zipfile.ZipFile(staged / "desktop.jar") as archive:
        if NODE_CLASS.replace(".", "/") + ".class" not in archive.namelist():
            raise ValueError("The desktop application is missing its internal node bootstrap")
    properties = temporary / "ZyrexNode.properties"
    properties.write_text("main-jar=desktop.jar\nmain-class=" + NODE_CLASS + "\n"
                          "description=Zyrex internal node process\n"
                          "java-options=" + " ".join(NODE_JAVA_OPTIONS) + "\n"
                          "win-console=false\nwin-shortcut=false\nwin-menu=false\nlauncher-as-service=false\n",
                          encoding="ascii")
    return ["--add-launcher", "ZyrexNode=" + str(properties)]


def windows_manifest_tool():
    executable = shutil.which("mt.exe")
    if executable:
        return regular_file(Path(executable))
    candidates = []
    for variable in ("ProgramFiles(x86)", "ProgramFiles"):
        directory = os.environ.get(variable)
        if directory:
            for candidate in (Path(directory) / "Windows Kits/10/bin").glob("*/x64/mt.exe"):
                version = candidate.parent.parent.name
                if re.fullmatch(r"[0-9]+(?:\.[0-9]+){3}", version) and candidate.is_file():
                    candidates.append((tuple(map(int, version.split("."))), candidate))
    if not candidates:
        raise RuntimeError("Windows packaging requires the Windows 10 or newer SDK manifest tool (mt.exe)")
    return regular_file(max(candidates, key=lambda item: item[0])[1])


def read_windows_manifest(path):
    if regular_file(path).stat().st_size > 1024 * 1024:
        raise ValueError("The generated Windows launcher manifest is too large")
    parser = ElementTree.XMLParser(target=ElementTree.TreeBuilder(insert_comments=True, insert_pis=True))
    root = ElementTree.fromstring(path.read_bytes(), parser=parser)
    if root.tag != "{" + ASSEMBLY_NAMESPACE + "}assembly" or root.get("manifestVersion") != "1.0":
        raise ValueError("The generated Windows launcher has an unexpected manifest root")
    return root


def manifest_signature(element):
    children = [manifest_signature(child) for child in element if isinstance(child.tag, str)]
    return element.tag, tuple(sorted(element.attrib.items())), (element.text or "").strip(), tuple(sorted(children))


def set_manifest_utf8(root):
    def single_child(parent, name):
        children = parent.findall(name)
        if len(children) > 1:
            raise ValueError("The generated Windows launcher manifest contains duplicate settings")
        return children[0] if children else ElementTree.SubElement(parent, name)

    application = single_child(root, "{" + APPLICATION_NAMESPACE + "}application")
    settings = single_child(application, "{" + APPLICATION_NAMESPACE + "}windowsSettings")
    tag = "{" + CODE_PAGE_NAMESPACE + "}activeCodePage"
    existing = [item for item in root.iter() if isinstance(item.tag, str) and item.tag.endswith("}activeCodePage")]
    if any(item not in list(settings) or item.tag != tag for item in existing) or len(existing) > 1:
        raise ValueError("The generated Windows launcher has an unexpected active code page setting")
    code_page = single_child(settings, tag)
    if code_page.attrib or list(code_page):
        raise ValueError("The Windows active code page setting must be a simple value")
    code_page.text = "UTF-8"


def enable_windows_utf8(image, temporary):
    """Set process-local UTF-8 before installer generation or future signing."""
    executable = windows_manifest_tool()
    ElementTree.register_namespace("", ASSEMBLY_NAMESPACE)
    ElementTree.register_namespace("asmv3", APPLICATION_NAMESPACE)
    ElementTree.register_namespace("ws2019", CODE_PAGE_NAMESPACE)
    for name in ("Zyrex", "ZyrexNode"):
        launcher = regular_file(image / (name + ".exe"))
        manifest = temporary / (name + ".manifest")
        verified = temporary / (name + ".verified.manifest")
        run([executable, "-nologo", "-inputresource:" + str(launcher) + ";#1", "-out:" + str(manifest)], timeout=30)
        root = read_windows_manifest(manifest)
        set_manifest_utf8(root)
        ElementTree.ElementTree(root).write(manifest, encoding="utf-8", xml_declaration=True)
        run([executable, "-nologo", "-manifest", manifest, "-validate_manifest"], timeout=30)
        run([executable, "-nologo", "-manifest", manifest, "-outputresource:" + str(launcher) + ";#1"], timeout=30)
        run([executable, "-nologo", "-inputresource:" + str(launcher) + ";#1", "-out:" + str(verified)], timeout=30)
        if manifest_signature(root) != manifest_signature(read_windows_manifest(verified)):
            raise ValueError("The embedded Windows launcher manifest did not preserve the requested settings")


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


def prepare_debian_installer(source, destination):
    """Keep desktop integration working on minimal Ubuntu installations."""
    with tempfile.TemporaryDirectory(prefix="zyrex-debian-") as temporary:
        unpacked = Path(temporary) / "package"
        run(["dpkg-deb", "--raw-extract", source, unpacked])
        postinst = regular_file(unpacked / "DEBIAN/postinst")
        script = postinst.read_text(encoding="utf-8")
        integration = r"(?m)^([ \t]*)xdg-desktop-menu install "
        if not script.startswith("#!/bin/sh\n") or len(re.findall(integration, script)) != 1:
            raise ValueError("Unexpected Debian desktop integration script")
        # xdg-utils can be installed without a desktop environment, leaving this
        # standard system menu directory absent. Create it during configuration,
        # before the generated shortcut registration, without hiding its errors.
        script = re.sub(integration, lambda match: match.group(1)
                        + "install -d -m 755 /usr/share/desktop-directories\n" + match.group(0), script)
        postinst.write_text(script, encoding="utf-8")
        run(["dpkg-deb", "--root-owner-group", "--build", unpacked, destination])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Directory containing desktop.jar and zyrex.jar")
    parser.add_argument("--runtime", type=Path, required=True, help="Unmodified native Java 11 runtime directory")
    parser.add_argument("--jpackage", default="jpackage", help="JDK 21 or newer native jpackage executable")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/desktop")
    parser.add_argument("--image-output", type=Path, help="Parent directory for the retained Zyrex application image")
    parser.add_argument("--platform", choices=("auto", "linux", "windows"), default="auto")
    parser.add_argument("--version", help="Optional numeric version; must match the desktop JAR manifest")
    parser.add_argument("--icon", type=Path, required=True, help="Linux PNG or Windows ICO icon")
    args = parser.parse_args()
    host = native_platform()
    if args.platform not in ("auto", host):
        parser.error("Native installers must be built on their target operating system")
    args.input = args.input.resolve(strict=True)
    release_version, args.version = application_version(args.input, args.version)
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
    basename = f"zyrex-desktop-{release_version}-{host}-amd64"
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
        extra_launchers = windows_node_launcher(temporary, staged) if host == "windows" else []
        run(common + ["--type", "app-image", "--input", staged, "--main-jar", "desktop.jar",
                      "--main-class", MAIN_CLASS, "--runtime-image", args.runtime, "--dest", image_parent,
                      "--icon", args.icon, "--java-options", "-Xms32m", "--java-options", "-Xmx256m",
                      "--java-options", "-Dfile.encoding=UTF-8"] + extra_launchers)
        if host == "windows":
            enable_windows_utf8(image, temporary)
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
        if host == "linux":
            prepare_debian_installer(built[0], installer)
        else:
            shutil.copyfile(built[0], installer)
        portable_archive(image, archive, host)
    with sums.open("x", encoding="utf-8", newline="\n") as output:
        for path in sorted((archive, installer)):
            output.write(f"{sha256(path)}  {path.name}\n")
    print(json.dumps({"platform": host, "applicationImage": str(image), "launcher": str(launcher),
                      "assets": [str(archive), str(installer), str(sums)]}, indent=2))


if __name__ == "__main__":
    main()
