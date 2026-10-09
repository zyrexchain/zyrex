#!/usr/bin/env python3
"""Check public language, branding and operator-specific documentation endpoints."""
import ipaddress
import re
import subprocess
import sys
from pathlib import Path


def main():
    names = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
    failures = []
    for name in filter(None, names):
        path = Path(name)
        try:
            text = path.read_text(encoding='utf-8')
        except (FileNotFoundError, UnicodeError):
            continue
        if re.search(r'[\u0400-\u052f]', text):
            failures.append(f'{name}: public text contains Cyrillic')
        branding_surface = (
            path.suffix == '.md' or name.startswith('src/main/resources/panel/') or
            name.startswith('config/') or name.startswith('src/main/resources/api/') or
            (name.startswith('src/main/resources/') and path.suffix == '.conf')
        )
        if name != 'README.md' and branding_surface:
            if re.search(r'\bergo(?:script)?\b|\berg\b|\bergoplatform\b', text, re.IGNORECASE):
                failures.append(f'{name}: public branding refers to the original product')
        if path.suffix == '.md':
            for value in re.findall(r'(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])', text):
                try:
                    address = ipaddress.ip_address(value)
                except ValueError:
                    continue
                if address.is_private and not address.is_loopback and not address.is_unspecified:
                    failures.append(f'{name}: documentation contains a private network address')
    if failures:
        print('\n'.join(sorted(set(failures))), file=sys.stderr)
        return 1
    print('Public content check passed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
