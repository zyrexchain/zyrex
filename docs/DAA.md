# Difficulty adjustment evidence

Zyrex's launched testnet uses the inherited legacy predictive calculator,
`DifficultyAdjustment.calculate`. The EIP-37 branch is excluded when Zyrex
chain settings are present. This verification keeps the live rules unchanged.
Changing them requires an announced consensus activation or a separate testnet
reset; the mainnet template is not an approved launch configuration.

The legacy calculator estimates work from epoch-end timestamps and difficulty,
predicts the next epoch using integer linear regression, and normalizes the result
through the native compact-difficulty serializer. When the prediction is below
one it returns the configured initial difficulty. It has no EIP-37 per-step caps.

## Profiles tested

The runner reads the actual source configuration files rather than copying their
parameters into a separate simulator. All three use eight epochs and a 60-second
target; their wall-clock response times differ substantially.

| Profile | Blocks per epoch | Target epoch duration | Eight-epoch target history | Initial difficulty |
| --- | ---: | --- | --- | ---: |
| Private devnet | 16 | 16 minutes | 2 hours 8 minutes | 1 |
| Public testnet | 64 | 64 minutes | 8 hours 32 minutes | 4,294,967,296 |
| Mainnet template, not launched | 2,048 | 34 hours 8 minutes | 11 days 9 hours 4 minutes | 1 |

Each deterministic scenario warms up for ten epochs, then runs for 32 stress
epochs. The calculator and compact serialization are the actual Scala consensus
methods. Header selection matches the live legacy branch, including omission of
the nonexistent height-zero header at the first recalculation.

Scenarios cover constant power, a fourfold increase, departures of 90% and 99%,
alternating fourfold and quarter power every two epochs, and timestamp movement
at the 600-second future allowance and into the past. Headers remain strictly
increasing and within the future allowance. Separate vectors exercise one-header
and non-increasing timestamp fallback, invalid interior timestamps, and the
different EIP-37 result on very compressed timestamps.

## Model and observations

These are expected-time arithmetic experiments, not mined or network-validated
blocks. A mature starting difficulty of 2^32 is used for comparable hashrate
steps. Each profile retains its own configured initial-difficulty fallback.
Additional runs start at difficulty one for the two templates that specify it.
Expected block times are rounded upward to whole milliseconds. The model omits
PoW variance, miner dataset generation, propagation, stale blocks, voting and
adversarial economics. It does not measure real recovery time or maximum TPS.

The constant scenario retains difficulty 2^32 and exactly 60 seconds per block.
After a fourfold power increase, the first epoch averages 15 seconds and later
overshoots to 79.286 seconds before returning to 60. Oscillating power produces
epoch averages from 7.767 to 895.705 seconds and does not settle within 32 epochs.

After an abrupt 90% departure, the first epoch remains at the previous difficulty
and averages 600 seconds per block. With 99% departure it averages 6,000 seconds.
That alone makes the first public-testnet epoch take 10 hours 40 minutes or
4 days 10 hours 40 minutes respectively. The corresponding mainnet-template
first epochs take 14 days 5 hours 20 minutes or 142 days 5 hours 20 minutes.
No adjustment happens before that epoch boundary in this model.

Both departures drive the predictive result below one after the third stress
epoch, triggering the initial-difficulty fallback. On public testnet, that means
returning to 2^32 despite the reduced power. The first three consecutive epochs
within 10% of the target finish after approximately 75.16 modeled hours for the
90% departure and 709.45 modeled hours for the 99% departure.

The devnet and mainnet-template fallback is one. At the mature modeled hashrate,
this reaches the one-millisecond rounding floor; that burst is a limitation of
the timing model and must not be interpreted as achievable network throughput.
Starting at difficulty one gives no further room to reduce difficulty after a
departure. These differences make the accelerated private test unsuitable as
evidence of acceptable public or mainnet behavior.

## Reproduction

Run the native stress suite and existing DAA regressions:

```sh
scripts/daa-check
```

The JSON contains 67 successful runs and two failed candidate runs, every stress
epoch's duration and difficulty, and
SHA-256 hashes of the calculator, compact serializer, header processor,
simulation source and all three network profiles. Keep execution artifacts in
ignored local storage; attach them to CI runs for review. The regression suite
also checks that the selected profile values have not drifted silently.

## EIP-37 comparison

The same runner also calls native `eip37Calculate` with the same profiles and
scenarios. This is an evaluated candidate, not the launched network's algorithm.
At the first recalculation there is only one existing endpoint, so this model
retains that difficulty; subsequent recalculations use EIP-37 with at least two
headers. Any activation implementation must define and test its own boundary.

At mature difficulty, the candidate's normalized changes stay within its native
half-to-one-and-a-half step limits. On the public-testnet profile, three epochs
within 10% of target finish after about 33.82 modeled hours for 90% departure and
262.63 modeled hours for 99% departure. The first slow epoch is unchanged because
both algorithms wait for the epoch boundary. Alternating power still does not
settle in 32 epochs, with modeled epoch averages from 6.994 to 540 seconds.
Reducing overshoot does not remove the consequences of a long epoch or near-total
loss of mining power.

The raw EIP-37 candidate returns zero at difficulty one under the 99% departure
input: both the classic integer estimate and `lastDifficulty / 2` round to zero.
Two native regression vectors confirm this, and the simulator stops those runs
instead of constructing further headers. Any candidate implementation needs an
explicit, tested minimum positive difficulty before activation. This evidence
does not approve a direct substitution of the native EIP-37 method.

A separate test-only candidate applies `max(1)` after the native EIP-37 result has
been compact-normalized. It keeps the mature simulation traces unchanged and
completes the difficulty-one departure scenarios with positive difficulty one.
Fixed native vectors cover difficulties 1, 2, 3, 2^32 and 2^240, epoch timestamp
intervals from one millisecond to one million times the target, normalized step
bounds, and rejection of equal endpoint timestamps. The guard prevents a zero
result; it cannot accelerate a difficulty-one chain after mining power departs.
This wrapper has not been activated in consensus.

## Launch decision

Legacy DAA is retained for the existing experimental testnet to preserve its
consensus. These results expose slow reaction, predictive overshoot and fallback
behavior; they do not justify the current mainnet template. Selection of a final
DAA and epoch length remains a launch gate. An EIP-37 candidate must be tested
separately with native validation, timestamp cases and the intended final
parameters before any activation is authorized.
