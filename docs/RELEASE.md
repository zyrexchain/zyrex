# Testnet release scope

Testnet alpha releases pin the source commit, native JAR SHA-256 and public genesis.
Release workflows build the checked-out commit, run pool, explorer and native
consensus regressions, and preserve source-bound bootstrap and DAA evidence.
Checksums identify the exact artifacts; they are not a complete security audit
or a claim of byte-identical builds on arbitrary toolchains.

The pool uses an opt-in atomic native work-cache snapshot to revoke old block
jobs and retain compatible cached transaction-only updates. Notifications are
coalesced per worker; duplicate work no longer churns job IDs. Accepted block
solutions survive a disconnected share-acknowledgement socket. Graceful shutdown
drains admitted writes and the native submission before closing executors. Work-result
diagnostics distinguish stale, duplicate, invalid and resource-limited shares
and are explicitly scoped to the current pool process lifetime. These changes
preserve the live consensus and emission rules.

Automatic transaction fees quote the exact native signed size, bounded recent
inclusions and current mempool pressure. Desktop and CLI confirmation bind the
recipient, fee and signed transaction ID; uncertain submissions preserve the
same signed transaction for explicit history checks and retry. Pool Auto fees
are capped and deducted proportionally from recipients before reservation.
Pending pool payments retain their original signed bytes and fee on retry.

Private accounting backups include uncheckpointed SQLite WAL data and preserve
pending signed payouts. Verification pins identity, bytes, integrity and row
counts; recovery requires a fresh destination and never overwrites a live database.
Synthetic TCP bursts check bounded admission and responsive miner history during
blocked PoW verification. They do not measure GPU throughput or production capacity.

Native releases include a resolved-library CycloneDX inventory and upstream
database notices. The inventory identifies exact external JAR bytes; offline
inventory is separate from an online vulnerability audit and excludes the JDK,
operating system and native database binary dependencies.

Private CI uses a disposable four-node network with its native sixty-second
target. The pool check allows thirty minutes for proof search, competition with
the native miner, reward maturity and confirmed payments to both receiving
wallets. Confirmation depths and native startup guards are unchanged. Failure
artifacts preserve public accounting state rather than reporting shares as
successful payments. Public DAA evidence and bootstrap run separately.

The network is experimental and may reset. Test coins have no monetary value,
guaranteed mainnet conversion or recovery guarantee. Use separate test-wallet
recovery phrases. Mainnet and its template parameters remain unapproved.

| Component | Verified scope | Remaining scope |
| --- | --- | --- |
| Native node | Full inherited node/core/wallet/database regressions; focused fork suites; private integration | Independent security review and final mainnet parameters |
| Pool | TCP bursts, bounded local abuse, capped fees, durable payouts, restart and reorganization regressions | Production-scale load and independent infrastructure |
| Explorer | Canonical indexing, P2PK/P2S/P2SH, spend and rollback | Long-history capacity |
| Ubuntu desktop | Native packaging, installation, GUI and bundled node checks | Broader desktop environments |
| Windows desktop | Native packaging, installation, GUI and bundled node checks | Broader Windows configurations |
| Linux CLI | Static x86-64/ARM64 builds; x86-64 Ubuntu and minimal-image execution | ARM64 runtime verification |
| GPU mining | One NVIDIA configuration exercised with native shares and payments | AMD, other devices and other miners |

Two bootstrap ports sharing one gateway do not provide independent ingress.
Artifacts explicitly label completed DAA/voting epochs, restart checks and
controlled failed-peer tests. A checkpoint below height 385 cannot demonstrate
three completed transitions of both public epoch schedules.

The inherited legacy DAA remains live. Native simulations in [DAA.md](DAA.md)
expose slow reaction and fallback behavior. The guarded EIP-37 activation hook
and optional shorter epochs are disabled in every source profile. Native header
and fork tests verify selection and rejection around activation boundaries;
these results do not authorize activation or establish mainnet readiness.
[Calendar evidence](CALENDAR.md) records unchanged height schedules at the
sixty-second target and native integer emission validation through its final nano-units.

Private vulnerability reporting is currently disabled in the GitHub repository.
A verified private reporting channel is required before broader public promotion;
do not publish recovery phrases or exploitable details in public issues.
