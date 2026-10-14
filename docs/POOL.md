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

## Work updates

New block parents revoke previous jobs through `mining.notify` with `clean_jobs=true`.
On a transaction-only refresh, compatible jobs remain valid only while the native
node includes their messages in its atomic mining-cache snapshot. Identical work
is not repeatedly assigned new job IDs. Each connection has a bounded notification
sender, so a slow worker does not delay updates to other workers.

Share verification rechecks job validity before durable credit. A block solution
is persisted and acknowledged without waiting for the node RPC; further solutions
for that work are paused until the node applies it or fresh node state permits
recovery. This preserves honest stale accounting during races and RPC failures.
The pool API reports accepted, stale and rejected work by reason under
`workResults`; these diagnostics cover the current pool process lifetime.
Network latency and competing blocks can still produce stale shares.

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
rounds, maturity constraints, signed-payment recovery and mining-job races.

Live Stratum mining has been exercised on the test network, including accepted
blocks, mandatory team outputs and confirmed pool payouts.

## Protocol provenance

The wire format follows the upstream
[Miningcore implementation](https://github.com/oliverw/miningcore).
The pool is a separate Python implementation; Miningcore is not bundled.
Autolykos verification follows the CC0 reference implementation identified in the README.
