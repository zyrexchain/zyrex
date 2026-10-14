# Difficulty adjustment evidence

Zyrex's launched testnet uses the inherited legacy predictive calculator,
`DifficultyAdjustment.calculate`. The upstream EIP-37 branch is excluded when Zyrex
chain settings are present. The separate Zyrex activation hook is disabled in all
source profiles. This verification keeps the live rules unchanged.
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

The JSON contains 67 successful comparison runs, two failed raw candidate runs,
36 disabled shorter-period proposals and three explicit difficulty-one growth
comparisons, every stress
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

The guarded native helper applies `max(1)` to the EIP-37 result and performs a
compact serialization cycle. It keeps the mature simulation traces unchanged and
completes the difficulty-one departure scenarios with positive difficulty one.
Fixed native vectors cover difficulties 1, 2, 3, 2^32 and 2^240, epoch timestamp
intervals from one millisecond to one million times the target, normalized step
bounds, and rejection of equal endpoint timestamps. The positive guard prevents
a zero result. A separate discrete bootstrap rule permits the smallest upward
integer step; neither guard can restore target time after a departure when
difficulty is already one.
The production guarded helper now has an optional activation hook; it remains
disabled on every existing network.

## Launch decision

Legacy DAA is retained for the existing experimental testnet to preserve its
consensus. These results expose slow reaction, predictive overshoot and fallback
behavior; they do not justify the current mainnet template. Selection of a final
DAA and epoch length remains a launch gate. An EIP-37 candidate must be tested
separately with native validation, timestamp cases and the intended final
parameters before any activation is authorized.


## Disabled activation hook

`zyrex.chain.zyrex.daaActivationHeight = null` preserves the complete legacy path.
An explicitly configured activation must be greater than one and satisfy
`(height - 1) % epochLength == 0`: it selects the first header after an existing
epoch boundary. At and after that height, recalculations use the native guarded
EIP-37 calculation, retaining the current epoch length and eight-epoch history.
Headers between recalculations retain their parent's difficulty. The configured
upstream EIP-37 epoch length must be absent or equal to the existing epoch length.

At the first possible boundary, the nonexistent height-zero endpoint is omitted.
A single existing endpoint retains its positive compact-normalized difficulty.
Later boundaries require all selected endpoints from the candidate's own branch,
positive endpoint difficulties, exact height spacing and strictly increasing
timestamps. The native result is floored at one and compact-normalized. The
original upstream calculator and all pre-activation rules remain unchanged.

At difficulty one, the raw EIP-37 upper cap `floor(1 * 3 / 2)` also equals one,
which prevents any upward adjustment. The guarded rule has one explicit discrete
exception: with at least two endpoints, if the native classic estimate
`lastDifficulty * targetInterval * epochLength / elapsedTime` exceeds one, it
returns compact-normalized difficulty two. Constant or reduced power retains
one; larger difficulties keep the native EIP-37 caps. This exception applies only
to the disabled guarded rule. Native headers and fourfold-power model vectors
test the transition, the rejected old target and subsequent difficulty growth.

Temporary native histories test accepted and rejected PoW headers before, at and
after activation, different endpoint histories on a fork, the first endpoint,
zero/negative difficulty and timestamp boundaries. Stress scenarios retain 90%
and 99% departures and difficulty-one startup. These tests establish rule
selection and validation, not acceptable recovery under real power loss.

Activation is a consensus choice that all operators must announce and configure
before the selected height. The field is not committed into the existing genesis
script, so conflicting configured heights produce a fork when their rules diverge.
No activation height is selected, deployed or authorized by this implementation;
a mainnet launch and any live activation still need a separate decision. A
non-boundary height is rejected rather than silently rounded. Existing public
64-block and mainnet-template 2,048-block epochs remain unchanged and require
separate consideration because both algorithms still wait for the next boundary.
See [the calendar audit](CALENDAR.md) for the independent height schedules.

## Disabled shorter-period proposal

`zyrex.chain.zyrex.daaActivationEpochLength = null` retains the existing period.
An explicit shorter period additionally requires an announced activation height,
must contain at least two blocks, be smaller than the existing period and divide
it exactly. The activation still falls on the first header after an old-period
boundary. The original `epochLength` remains unchanged before that height.

With a shorter period configured, activation starts a new endpoint history at
height `activationHeight - 1`. The first activated header retains that parent's
difficulty; later recalculations use only that anchor and post-activation
endpoints spaced by the shorter period. Earlier old-period timestamps never
enter the new-period arithmetic. At most eight intervals are retained. Native
header validation and continuous proof construction/checking use the same
endpoint-selection helper, including alternate branches and transition bounds.

Separate proposal simulations warm up for ten original epochs, then model
32 shorter epochs with periods four and sixteen for the public and mainnet
templates. They also exercise difficulty-one startup. Native headers test the
old-period boundary, first anchored result, the next shorter boundary, rejection
of mixed-history targets and a competing branch. These are disabled options,
not new network settings. Selection of a height and period remains a consensus
decision; all operators must configure the identical values in advance.

Shortening the period limits the number of slow blocks before the first
adjustment. It still waits for the first new boundary: after 99% modeled power
loss, four blocks take 6 hours 40 minutes and sixteen take 26 hours 40 minutes
at the old difficulty. A positive floor cannot restore target timing when
difficulty is already one. No proposal here guarantees real mining recovery.
