# Integer emission

One ZYRX is 1,000,000,000 nanoZYRX. Heights 1 through 432,000 have a
100,000,000,000-nanoZYRX subsidy. Each subsequent 432,000-block epoch halves
the preceding integer subsidy, rounded down. Emission ends after 37 nonzero epochs.
Genesis is height 1 and follows the first subsidy allocation.

Each block assigns `floor(subsidy / 20)` to each of two independent team
P2PK wallets. The miner receives `subsidy - 2 * floor(subsidy / 20)`.
Transaction fees are separate from the subsidy. Integer rounding favors the miner;
90% miners and 10% team describe the intended proportions, not fractional nano units.
These rules and both recipients are enforced by consensus.

| Recipient | Exact lifetime emission, ZYRX |
| --- | ---: |
| Miners | 77,760,000.003024 |
| Each team wallet | 4,319,999.995248 |
| Both team wallets | 8,639,999.990496 |
| Total | 86,399,999.99352 |

The ideal geometric supply of 86,400,000 ZYRX is theoretical. No private-testnet
key is approved for mainnet; final mainnet recipients and genesis remain unset.
