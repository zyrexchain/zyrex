Portable command-line wallet for the Zyrex public testnet.

- Static Linux binaries for x86_64 and ARM64, with no Java, Python or glibc dependency.
- Wallet creation and recovery, address generation, balances and transfers through your own Zyrex node.
- Exact integer amounts and pinned network identity.
- Automatic fees use the signed transaction size and recent node observations, with a default 0.01 ZYRX ceiling.
- Manual `--fee` and Auto `--max-fee` overrides; `retry` checks history and resends the same saved signed transaction.

Requires a running Zyrex node and its wallet API credentials. Wallet keys and signing remain in the node.
Uncertain submissions retain an owner-only recovery record; preparing a second transfer is blocked until the saved transfer is checked.

x86_64 execution was verified on Ubuntu 24.04 and an empty Linux container.
ARM64 is cross-compiled and has not been hardware-tested.

The archives include the executable and license notices. `SHA256SUMS` contains their SHA-256 checksums.
This is a testnet prerelease.
