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

## Accounting

The payout scheme is PROP with a 0% pool fee. SQLite persists accepted shares,
rounds, balances and signed payouts. Consensus assigns 90% of the subsidy to
miners and 10% to the team before pool distribution.

Balances and share weights use integer units. A largest-remainder allocation
conserves every nanoZYRX. Payout transaction fees are shared by recipients.
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

NVIDIA RTX 4070 Ti mining with lolMiner 1.98a was verified against the private
network, including accepted blocks, mandatory founder outputs and confirmed
pool payouts. This does not verify every GPU model or miner implementation.

## Protocol provenance

The wire format follows the upstream
[Miningcore implementation](https://github.com/oliverw/miningcore).
The pool is a separate Python implementation; Miningcore is not bundled.
Autolykos verification follows the CC0 reference implementation identified in the README.
