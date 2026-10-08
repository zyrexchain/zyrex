# Zyrex FAQ

**Is Zyrex connected to Ergo mainnet?**
No. Zyrex has its own genesis, network magic, address prefixes and chain state.
It preserves the upstream eUTXO model, ErgoScript and Autolykos v2.

**How is the block subsidy distributed?**
The miner receives 90%; two founder wallets receive 5% each. The emission contract
and native consensus enforce the payments. Integer rounding goes to the miner.

**Is there a premine?**
No spendable allocation exists before mining. The initial reserve is locked under
the emission contract and released only through validated block subsidies.

**Is mainnet available?**
No. Private-testnet behavior has been verified; public-testnet deployment is in progress.

**Where are the project details?**
See the [README](README.md) and [upstream provenance](docs/UPSTREAM.md).
