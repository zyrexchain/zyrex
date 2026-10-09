# Zyrex mining pool

The pool uses Autolykos v2 and the Miningcore-compatible Stratum v1 wire format.
The endpoints below serve the public testnet.

| Service | Endpoint |
| --- | --- |
| Website | https://pool.zyrexchain.com |
| Stratum TCP | `stratum+tcp://stratum.zyrexchain.com:3333` |
| Stratum TLS | `stratum+ssl://stratum.zyrexchain.com:3443` |

The miner username is `ZYREX_ADDRESS.worker_name`; password `x` enables automatic
share difficulty. A password such as `d=0.1` selects a fixed Miningcore-compatible
difficulty. TLS requires support from the chosen miner.

## Miner accounts

Open a payout address on the pool website to view its read-only miner account.
The shareable page is `/miner/ADDRESS`. It shows mature unpaid credits, pending
payouts, lifetime confirmed payments, workers and weighted round contribution.
Payout history is paginated across all recorded payments for that address;
amounts and network fees are displayed in exact integer units. Legacy payments
retain their recorded net amounts without inventing missing gross or fee data.
The account never requests a password, recovery phrase or wallet private key.

## Accounting

The payout scheme is PROP with a 0% pool fee. SQLite persists accepted shares,
rounds, balances and signed payouts. Consensus assigns 90% of the subsidy to
miners and 10% to the team before pool distribution. Each of two independent team
wallets receives `floor(subsidy / 20)` nanoZYRX; miners receive the integer remainder.
The recipients are separate P2PK outputs, not a multisignature wallet.

Balances and share weights use integer units. A largest-remainder allocation
conserves every nanoZYRX. Each payout deducts one actual network transaction fee
from its recipients, in proportion to their gross payment amounts. The pool does
not deduct a fee for every mined block. The website shows each recipient's gross
debit, fee share and net payment. Unspent legacy fee reserves are refunded once
through an audited adjustment; historical credits and signed payments are retained.
Credits require canonical-chain confirmation beyond the configured reward maturity;
amounts below the minimum payout remain accrued.

Duplicate, invalid, stale and unauthorized shares are not credited. Orphaned
round work carries into the next canonical round. A deep reorganization of an
already credited block pauses payments for operator review.

Signed payout transactions are persisted before broadcast. Retries reuse the
same transaction ID and reserved balances, preventing duplicate payments after
an RPC timeout or process restart. The database is pinned to the genesis and mining key.

## Verification

Automated tests cover reference PoW vectors, network address isolation,
actual TCP Stratum messages, rejected shares, exact PROP allocations, orphan
rounds, maturity constraints and signed-payment recovery.

Live Stratum mining has been exercised on the test network, including accepted
blocks, mandatory team outputs and confirmed pool payouts.

## Protocol provenance

The wire format follows the upstream
[Miningcore implementation](https://github.com/oliverw/miningcore).
The pool is a separate Python implementation; Miningcore is not bundled.
Autolykos verification follows the CC0 reference implementation identified in the README.
