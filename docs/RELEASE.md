# Testnet release scope

Testnet alpha releases pin the source commit, native JAR SHA-256 and public genesis.
Release workflows build the checked-out commit, run pool, explorer and native
consensus regressions, and preserve source-bound bootstrap and DAA evidence.
Checksums identify the exact artifacts; they are not a complete security audit
or a claim of byte-identical builds on arbitrary toolchains.

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
| Native node | Selected consensus, network, PoW and DAA suites; private integration | Full inherited regression and dependency audit |
| Pool | TCP protocol, bounded local abuse, payouts, restart and reorganization regressions | Production-scale load and independent infrastructure |
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
expose slow reaction and fallback behavior; the guarded EIP-37 model is a candidate,
not an activated consensus change or a mainnet-readiness result.

Private vulnerability reporting is currently disabled in the GitHub repository.
A verified private reporting channel is required before broader public promotion;
do not publish recovery phrases or exploitable details in public issues.
