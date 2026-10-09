#!/usr/bin/env python3
"""Read-only canonical-chain explorer backed by a local SQLite index."""
import argparse
import json
import logging
import re
import sqlite3
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit
from pow import ALPHABET, address_bytes, digest, validate_miner_address


# Canonical Pay2SHAddress.script in the pinned scripting SDK (version 6.0.7).
# It checks the 192-bit hash of context variable 126 and evaluates that variable.
P2SH_PREFIX = bytes.fromhex('00ea02d193b4cbe4e37e0e040004300e18')
P2SH_SUFFIX = bytes.fromhex('d4087e')


def address_script(address):
    """Resolve an address to its canonical locking script, including P2S aliases."""
    raw = address_bytes(address)
    if raw[0] not in (65, 66, 67):
        raise ValueError('Address belongs to another network')
    if raw[0] == 65:
        validate_miner_address(address, prefix=64)
        return (bytes.fromhex('0008cd') + raw[1:]).hex()
    if raw[0] == 66:
        if len(raw) != 25:
            raise ValueError('Invalid P2SH script hash length')
        return (P2SH_PREFIX + raw[1:] + P2SH_SUFFIX).hex()
    if len(raw) < 3:
        raise ValueError('Invalid P2S script length')
    return raw[1:].hex()


def encode_address(payload):
    raw = payload + digest(payload)[:4]
    value = int.from_bytes(raw, 'big')
    result = ''
    while value:
        value, digit = divmod(value, 58)
        result = ALPHABET[digit] + result
    return 'ZRX' + '1' * (len(raw) - len(raw.lstrip(b'\0'))) + result


def script_address(script):
    raw = bytes.fromhex(script)
    if len(raw) == 36 and raw[:3] == bytes.fromhex('0008cd'):
        return encode_address(b'\x41' + raw[3:])
    if len(raw) == len(P2SH_PREFIX) + 24 + len(P2SH_SUFFIX) and raw.startswith(P2SH_PREFIX) and raw.endswith(P2SH_SUFFIX):
        return encode_address(b'\x42' + raw[len(P2SH_PREFIX):-len(P2SH_SUFFIX)])
    return encode_address(b'\x43' + raw)


def public_json(value):
    if isinstance(value, dict):
        return {key: str(item) if key in ('value', 'amount', 'difficulty') and isinstance(item, int)
                else public_json(item) for key, item in value.items()}
    if isinstance(value, list):
        return [public_json(item) for item in value]
    return value


class Index:
    def __init__(self, config):
        self.config = config
        self.lock = threading.RLock()
        self.info = None
        self.error = 'Waiting for node'
        self.updated = None
        self.db = sqlite3.connect(config['database'], check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
            PRAGMA journal_mode=WAL;
            PRAGMA synchronous=FULL;
            PRAGMA foreign_keys=ON;
            CREATE TABLE IF NOT EXISTS identity(genesis TEXT PRIMARY KEY);
            CREATE TABLE IF NOT EXISTS blocks(height INTEGER PRIMARY KEY,id TEXT UNIQUE,raw TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS transactions(id TEXT PRIMARY KEY,height INTEGER REFERENCES blocks(height)
                ON DELETE CASCADE,raw TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS boxes(id TEXT PRIMARY KEY,tx TEXT,height INTEGER REFERENCES blocks(height)
                ON DELETE CASCADE,value TEXT,script TEXT,address TEXT,spent_by TEXT,spent_height INTEGER);
            CREATE INDEX IF NOT EXISTS boxes_address ON boxes(address);
            CREATE INDEX IF NOT EXISTS boxes_script ON boxes(script COLLATE NOCASE);
            CREATE INDEX IF NOT EXISTS transactions_height ON transactions(height);
        ''')
        existing = self.db.execute('SELECT genesis FROM identity').fetchone()
        if existing and existing[0] != config['genesisId']:
            raise ValueError('Explorer database belongs to a different genesis')
        with self.db:
            self.db.execute('INSERT OR IGNORE INTO identity VALUES(?)', (config['genesisId'],))

    def rpc(self, path):
        request = urllib.request.Request(self.config['nodeUrl'].rstrip('/') + path)
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=10) as response:
            raw = response.read(4 * 1024 * 1024 + 1)
            if len(raw) > 4 * 1024 * 1024:
                raise ValueError('Node response exceeds explorer limit')
            return json.loads(raw)

    def height(self):
        with self.lock:
            return self.db.execute('SELECT COALESCE(MAX(height),0) FROM blocks').fetchone()[0]

    def canonical(self, height):
        headers = self.rpc(f'/blocks/chainSlice?fromHeight={height-1}&toHeight={height}')
        return next((header for header in headers if header['height'] == height), None)

    def rollback(self, height):
        with self.lock, self.db:
            self.db.execute('UPDATE boxes SET spent_by=NULL,spent_height=NULL WHERE spent_height>?', (height,))
            self.db.execute('DELETE FROM blocks WHERE height>?', (height,))

    def append(self, block):
        header = block['header']
        height = header['height']
        transactions = block['blockTransactions']['transactions']
        with self.lock, self.db:
            previous = self.db.execute('SELECT id FROM blocks WHERE height=?', (height-1,)).fetchone()
            if height != self.height() + 1 or (previous and previous[0] != header['parentId']):
                raise ValueError('Noncontiguous canonical block')
            if height == 1 and header['id'] != self.config['genesisId']:
                raise ValueError('Foreign genesis')
            self.db.execute('INSERT INTO blocks VALUES(?,?,?)', (height, header['id'], json.dumps(public_json(block))))
            for tx in transactions:
                self.db.execute('INSERT INTO transactions VALUES(?,?,?)', (tx['id'], height, json.dumps(public_json(tx))))
                for inp in tx['inputs']:
                    cursor = self.db.execute('UPDATE boxes SET spent_by=?,spent_height=? WHERE id=? AND spent_by IS NULL',
                                             (tx['id'], height, inp['boxId']))
                    if cursor.rowcount != 1 and height != 1:
                        raise ValueError('Missing or already spent indexed input')
                for out in tx['outputs']:
                    self.db.execute('INSERT INTO boxes VALUES(?,?,?,?,?,?,NULL,NULL)',
                        (out['boxId'], tx['id'], height, str(out['value']), out['ergoTree'], script_address(out['ergoTree'])))

    def sync(self):
        info = self.rpc('/info')
        if info['network'] != 'testnet' or info['genesisBlockId'] != self.config['genesisId']:
            raise ValueError('Node identity mismatch')
        full_height = info['fullHeight']
        height = self.height()
        common = min(height, full_height)
        while common:
            with self.lock:
                local = self.db.execute('SELECT id FROM blocks WHERE height=?', (common,)).fetchone()
            header = self.canonical(common)
            if not header:
                raise ValueError('Canonical header unavailable')
            if local[0] == header['id']:
                break
            common -= 1
        if common != height:
            self.rollback(common)
        for next_height in range(common+1, min(full_height, common+128)+1):
            header = self.canonical(next_height)
            if not header:
                raise ValueError('Canonical header unavailable')
            block = self.rpc('/blocks/' + header['id'])
            if block['header']['id'] != header['id']:
                raise ValueError('Block identity mismatch')
            self.append(block)
        with self.lock:
            self.info = {key: info.get(key) for key in ('fullHeight', 'headersHeight', 'peersCount', 'bestFullHeaderId')}
            self.updated = int(time.time())
            self.error = None

    def run(self):
        while True:
            try:
                self.sync()
            except Exception as error:
                logging.warning('Explorer synchronization retry: %s', type(error).__name__)
                with self.lock:
                    self.error = 'Node synchronization unavailable'
            time.sleep(3)

    def query(self, target):
        path = urlsplit(target)
        args = parse_qs(path.query)
        with self.lock:
            height = self.height()
            if path.path in ('/api/status', '/health'):
                remaining = height
                emitted = 0
                for period in range(37):
                    count = min(remaining, 432000)
                    emitted += count * (100000000000 >> period)
                    remaining -= count
                return {'network': 'testnet', 'coin': 'ZYRX', 'genesisId': self.config['genesisId'],
                        'indexedHeight': height, 'node': self.info, 'updatedAt': self.updated,
                        'emittedNano': str(emitted), 'ready': self.error is None and height > 0,
                        'error': self.error}
            if path.path == '/api/blocks':
                limit = min(100, max(1, int(args.get('limit', ['20'])[0])))
                offset = max(0, int(args.get('offset', ['0'])[0]))
                rows = self.db.execute('SELECT raw FROM blocks ORDER BY height DESC LIMIT ? OFFSET ?', (limit, offset))
                result = []
                for row in rows:
                    block = json.loads(row[0])
                    result.append(dict(block['header'], transactionCount=len(block['blockTransactions']['transactions'])))
                return result
            key = unquote(path.path.rsplit('/', 1)[-1])
            if path.path.startswith('/api/block/'):
                if re.fullmatch(r'[0-9]{1,10}', key):
                    row = self.db.execute('SELECT raw FROM blocks WHERE height=?', (int(key),)).fetchone()
                elif re.fullmatch(r'[0-9a-f]{64}', key):
                    row = self.db.execute('SELECT raw FROM blocks WHERE id=?', (key,)).fetchone()
                else:
                    raise ValueError('Invalid block identifier')
                if row:
                    return json.loads(row[0])
            elif path.path.startswith('/api/tx/') and re.fullmatch(r'[0-9a-f]{64}', key):
                row = self.db.execute('SELECT raw,height FROM transactions WHERE id=?', (key,)).fetchone()
                if row:
                    return dict(json.loads(row[0]), height=row[1], confirmations=height-row[1]+1)
            elif path.path.startswith('/api/address/'):
                script = address_script(key)
                balance = sum(int(row[0]) for row in self.db.execute(
                    'SELECT value FROM boxes WHERE script=? COLLATE NOCASE AND spent_by IS NULL', (script,)))
                boxes = [dict(row) for row in self.db.execute(
                    'SELECT id,tx,height,value,spent_by FROM boxes WHERE script=? COLLATE NOCASE '
                    'ORDER BY height DESC LIMIT 100', (script,))]
                return {'address': key, 'balanceNano': str(balance), 'indexedHeight': height, 'boxes': boxes}
        raise LookupError('Not found')


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(10)
        super().setup()

    def do_GET(self):
        try:
            if urlsplit(self.path).path == '/':
                body = Path(__file__).with_name('index.html').read_bytes()
                content_type, status = 'text/html; charset=utf-8', 200
            else:
                result = self.server.index.query(self.path)
                body = json.dumps(result).encode()
                content_type, status = 'application/json', 200
                if self.path == '/health' and not result['ready']:
                    status = 503
        except (ValueError, LookupError) as error:
            status = 400 if isinstance(error, ValueError) else 404
            content_type, body = 'application/json', json.dumps({'error': str(error)}).encode()
        except Exception:
            status, content_type, body = 503, 'application/json', b'{"error":"Explorer temporarily unavailable"}'
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)

    do_HEAD = do_GET

    def log_message(self, message, *args):
        pass


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64

    def __init__(self, *args):
        self.slots = threading.BoundedSemaphore(32)
        super().__init__(*args)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    config = json.loads(Path(parser.parse_args().config).read_text())
    index = Index(config)
    threading.Thread(target=index.run, daemon=True).start()
    server = Server(('0.0.0.0', 8080), Handler)
    server.index = index
    server.serve_forever()


if __name__ == '__main__':
    main()
