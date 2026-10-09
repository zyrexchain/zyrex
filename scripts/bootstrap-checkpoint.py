#!/usr/bin/env python3
"""Fetch a public checkpoint for a fresh-node bootstrap check."""
import argparse
import json
import ssl
import urllib.request
from pathlib import Path


GENESIS = '0f6e2d9181f10231dafa9e1aa3eb297218204e2e2c38f3c65ae0ab367629d336'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, stream, code, message, headers, new_url):
        raise ValueError('Checkpoint redirects are not allowed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), NoRedirect(),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()))

    def fetch(path):
        request = urllib.request.Request('https://explorer.zyrexchain.com' + path,
                                         headers={'User-Agent': 'Zyrex-bootstrap-check'})
        with opener.open(request, timeout=15) as response:
            data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024:
            raise ValueError('Checkpoint response is too large')
        return json.loads(data)

    status = fetch('/api/status')
    if status['network'] != 'testnet' or status['genesisId'] != GENESIS or not status['ready']:
        raise ValueError('Public testnet checkpoint source is not ready or has a foreign identity')
    height = min(status['indexedHeight'], status['node']['fullHeight'])
    if height <= 1:
        raise ValueError('A bootstrap check requires blocks beyond the bundled genesis')
    block = fetch('/api/block/' + str(height))
    header = block['header']
    if header['height'] != height or block['blockTransactions']['headerId'] != header['id']:
        raise ValueError('Checkpoint block is inconsistent')
    if height == status['node']['fullHeight'] and header['id'] != status['node']['bestFullHeaderId']:
        raise ValueError('The public chain changed while fetching the checkpoint; retry the check')
    expected = {'network': 'testnet', 'genesisBlockId': GENESIS,
                'fullHeight': height, 'headersHeight': height,
                'bestFullHeaderId': header['id'], 'stateRoot': header['stateRoot']}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(expected, indent=2) + '\n')
    print(json.dumps(expected, indent=2))


if __name__ == '__main__':
    main()
