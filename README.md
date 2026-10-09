# Zyrex (ZYRX)

Zyrex is an independent Proof of Work blockchain inspired by
[Ergo](https://github.com/ergoplatform/ergo) and built from its open-source code.
It retains eUTXO, programmable contracts and Autolykos v2.

| Tokenomics | Value |
| --- | --- |
| Algorithm | Autolykos v2 from the first block |
| Target block time | 60 seconds |
| Initial block reward | 100 ZYRX |
| Reward allocation | 90% miners, 10% team |
| Premine | 0% |
| Halving | Every 432,000 blocks |
| Maximum supply | 86,400,000 ZYRX theoretical |
| Exact integer emission | 86,399,999.99352 ZYRX |
| Difficulty adjustment | Original DAA adapted to 60 seconds |

Team payments are enforced by consensus. The team allocation goes to two independent
wallets: each receives `floor(reward / 20)` nanoZYRX; miners receive the remainder.

Public testnet is experimental and may reset. Test coins have no monetary value
or guaranteed mainnet conversion. Use separate test-wallet recovery phrases.
Mainnet is not launched.
Downloads: [GitHub Releases](https://github.com/zyrexchain/zyrex/releases).
The upstream [CC0 license](LICENSE) and attribution are retained.
