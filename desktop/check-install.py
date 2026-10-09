#!/usr/bin/env python3
"""Install and remove a native package while retaining the isolated QA wallet data."""
import argparse
import hashlib
from pathlib import Path
import platform
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent.parent


def snapshot(directory):
    files = {}
    for file in directory.rglob('*'):
        if file.is_file():
            with file.open('rb') as stream:
                files[str(file.relative_to(directory))] = hashlib.file_digest(stream, 'sha256').hexdigest()
    if not files or not any('keystore' in file for file in files):
        raise ValueError('Installer checks require an actual encrypted QA wallet')
    return files


def run(command, timeout=240):
    result = subprocess.run([str(arg) for arg in command], timeout=timeout)
    if result.returncode not in (0, 3010):
        raise RuntimeError('Native installer check failed with exit code ' + str(result.returncode))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    args = parser.parse_args()
    data = args.data.resolve(strict=True)
    before = snapshot(data)
    windows = platform.system() == 'Windows'
    suffix = '.exe' if windows else '.deb'
    installer, = (ROOT / 'artifacts/desktop').glob('*' + suffix)
    if windows:
        with tempfile.TemporaryDirectory(prefix='zyrex-installed-') as temporary:
            install = Path(temporary) / 'Zyrex wallet \u03a9'
            run([installer, '/qn', '/norestart', 'INSTALLDIR=' + str(install)])
            try:
                launcher = install / 'Zyrex.exe'
                if not launcher.is_file():
                    raise RuntimeError('Windows installer did not create its native launcher')
                run([launcher, '--version'], timeout=30)
            finally:
                run([installer, 'uninstall'])
            if (install / 'Zyrex.exe').exists():
                raise RuntimeError('Windows uninstall did not remove the application')
    else:
        run(['sudo', 'dpkg', '-i', installer])
        try:
            launcher = Path('/opt/zyrex-desktop/bin/Zyrex')
            if not launcher.is_file():
                raise RuntimeError('Debian installer did not create its native launcher')
            run([launcher, '--version'], timeout=30)
        finally:
            run(['sudo', 'dpkg', '--remove', 'zyrex-desktop'])
    if snapshot(data) != before:
        raise RuntimeError('Native uninstall changed the QA wallet data')
    print('Native installation, launcher execution and wallet retention verified')


if __name__ == '__main__':
    main()
