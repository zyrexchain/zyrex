#!/usr/bin/env python3
"""Run executable desktop regressions and native packaged GUI checks."""
import argparse
import os
from pathlib import Path
import platform
import subprocess


ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path)
    parser.add_argument('--data', type=Path, default=ROOT / '.local/desktop-gui-data')
    parser.add_argument('--output', type=Path, default=ROOT / '.local/desktop-gui-result')
    args = parser.parse_args()
    tests = ROOT / 'desktop/target/desktop-tests.jar'
    if args.image is None:
        jars = [ROOT / 'desktop/target/input/desktop.jar', ROOT / 'desktop/target/input/zyrex.jar', tests]
        subprocess.run(['java', '-Djava.awt.headless=true', '-cp', os.pathsep.join(map(str, jars)),
                        'org.zyrexchain.desktop.BackendTests'], check=True, timeout=300)
        return
    image = args.image.resolve(strict=True)
    windows = platform.system() == 'Windows'
    runtime = image / ('runtime' if windows else 'lib/runtime')
    application = image / ('app' if windows else 'lib/app')
    java = runtime / 'bin' / ('java.exe' if windows else 'java')
    launcher = image / ('Zyrex.exe' if windows else 'bin/Zyrex')
    subprocess.run([str(launcher), '--version'], check=True, timeout=30)
    classpath = os.pathsep.join(map(str, [application / 'desktop.jar', application / 'zyrex.jar', tests]))
    command = [str(java), '-cp', classpath, 'org.zyrexchain.desktop.GuiSmoke', str(args.data.resolve()),
               str(args.output.resolve()), str(java), str(application / 'zyrex.jar')]
    if not windows:
        command = ['xvfb-run', '-a', '-s', '-screen 0 1400x1000x24', *command]
    subprocess.run(command, check=True, timeout=600)


if __name__ == '__main__':
    main()
