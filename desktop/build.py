#!/usr/bin/env python3
"""Compile the desktop application against the native node without external GUI dependencies."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node-jar', type=Path, default=ROOT / 'target/scala-2.12/zyrex.jar')
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--jar', default='jar')
    parser.add_argument('--tests', action='store_true')
    args = parser.parse_args()
    node = args.node_jar.resolve(strict=True)
    with zipfile.ZipFile(node) as archive:
        if 'org/zyrexchain/ZyrexApp.class' not in archive.namelist():
            raise ValueError('A current Zyrex node build is required')
    target = ROOT / 'desktop/target'
    classes = target / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / 'desktop/src/main/java').rglob('*.java'))
    if not sources:
        raise ValueError('Desktop application sources are missing')
    subprocess.run([args.javac, '--release', '11', '-encoding', 'UTF-8', '-cp', str(node),
                    '-d', str(classes), *map(str, sources)], check=True)
    input_dir = target / 'input'
    input_dir.mkdir(exist_ok=True)
    manifest = target / 'MANIFEST.MF'
    app_source = ROOT / 'desktop/src/main/java/org/zyrexchain/desktop/DesktopApp.java'
    version = re.search(r'VERSION = "([0-9]+\.[0-9]+\.[0-9]+-testnet)"', app_source.read_text()).group(1)
    manifest.write_text('Manifest-Version: 1.0\nMain-Class: org.zyrexchain.desktop.DesktopApp\n'
                        'Class-Path: zyrex.jar\nImplementation-Version: ' + version + '\n\n')
    subprocess.run([args.jar, '--create', '--file', str(input_dir / 'desktop.jar'),
                    '--manifest', str(manifest), '-C', str(classes), '.',
                    '-C', str(ROOT / 'desktop/src/main/resources'), '.'], check=True)
    shutil.copy2(node, input_dir / 'zyrex.jar')
    shutil.copy2(ROOT / 'LICENSE', input_dir / 'LICENSE')
    if args.tests:
        tests = sorted((ROOT / 'desktop/src/test/java').rglob('*.java'))
        test_classes = target / 'test-classes'
        test_classes.mkdir(exist_ok=True)
        classpath = os.pathsep.join(map(str, [input_dir / 'desktop.jar', node]))
        subprocess.run([args.javac, '--release', '11', '-encoding', 'UTF-8', '-cp', classpath,
                        '-d', str(test_classes), *map(str, tests)], check=True)
        subprocess.run([args.jar, '--create', '--file', str(target / 'desktop-tests.jar'),
                        '-C', str(test_classes), '.'], check=True)
    print('Desktop application payload prepared')


if __name__ == '__main__':
    main()
