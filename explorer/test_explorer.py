import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from server import Index, script_address
from pow import address_bytes


class CanonicalIndexTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        source = Path(__file__).resolve().parents[1] / 'src/main/resources/genesis/testnet.json'
        self.genesis = json.loads(source.read_text())
        self.config = {'genesisId': self.genesis['header']['id'], 'database': self.directory.name + '/index.sqlite'}
        self.index = Index(self.config)
        self.index.append(self.genesis)
        self.founder = self.genesis['blockTransactions']['transactions'][0]['outputs'][2]
        self.address = script_address(self.founder['ergoTree'])

    def tearDown(self):
        self.index.db.close()
        self.directory.cleanup()

    def next_block(self, ident='a', parent=None):
        script = self.genesis['blockTransactions']['transactions'][0]['outputs'][3]['ergoTree']
        return {'header': {'height': 2, 'id': ident*64, 'parentId': parent or self.config['genesisId']},
                'blockTransactions': {'transactions': [{'id': 'b'*64, 'inputs': [{'boxId': self.founder['boxId']}],
                    'outputs': [{'boxId': 'c'*64, 'value': self.founder['value'], 'ergoTree': script}]}]}}

    def test_native_genesis_balances_use_exact_integer_strings(self):
        account = self.index.query('/api/address/' + self.address)
        self.assertEqual(account['balanceNano'], '5000000000')
        self.assertEqual(address_bytes(self.address)[0], 65)
        reserve = self.genesis['blockTransactions']['transactions'][0]['outputs'][0]
        stored = self.index.query('/api/block/1')
        self.assertEqual(stored['blockTransactions']['transactions'][0]['outputs'][0]['value'], str(reserve['value']))

    def test_spend_and_rollback_restore_balance_and_remove_orphan_transactions(self):
        self.index.append(self.next_block())
        self.assertEqual(self.index.query('/api/address/' + self.address)['balanceNano'], '0')
        self.index.rollback(1)
        self.assertEqual(self.index.query('/api/address/' + self.address)['balanceNano'], '5000000000')
        with self.assertRaises(LookupError):
            self.index.query('/api/tx/' + 'b'*64)
        self.index.append(self.next_block(ident='d'))
        self.assertEqual(self.index.height(), 2)

    def test_missing_input_rolls_back_the_entire_block(self):
        block = self.next_block()
        block['blockTransactions']['transactions'][0]['inputs'][0]['boxId'] = 'f'*64
        with self.assertRaises(ValueError):
            self.index.append(block)
        self.assertEqual(self.index.height(), 1)
        self.assertEqual(self.index.query('/api/address/' + self.address)['balanceNano'], '5000000000')

    def test_foreign_database_and_network_are_rejected(self):
        with self.assertRaises(ValueError):
            Index(dict(self.config, genesisId='f'*64))
        self.index.rpc = Mock(return_value={'network': 'devnet', 'genesisBlockId': self.config['genesisId']})
        with self.assertRaises(ValueError):
            self.index.sync()

    def test_canonical_fork_replaces_index_without_mixing_balances(self):
        self.index.append(self.next_block())
        alternate = self.next_block(ident='d')
        def rpc(path):
            if path == '/info':
                return {'network': 'testnet', 'genesisBlockId': self.config['genesisId'], 'fullHeight': 2}
            if path.endswith('toHeight=1'):
                return [self.genesis['header']]
            if path.endswith('toHeight=2'):
                return [alternate['header']]
            if path == '/blocks/' + 'd'*64:
                return alternate
            raise AssertionError(path)
        self.index.rpc = rpc
        self.index.sync()
        self.assertEqual(self.index.query('/api/block/2')['header']['id'], 'd'*64)
        self.assertEqual(self.index.query('/api/address/' + self.address)['balanceNano'], '0')
