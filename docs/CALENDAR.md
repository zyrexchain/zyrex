# Height schedules at the 60-second target

Height schedules remain consensus rules. A 60-second target changes their nominal
calendar duration compared with the inherited 120-second target; it does not
change their block thresholds. Actual mining power, difficulty adjustment,
variance and interruptions determine elapsed time.

| Rule | Current threshold | Nominal duration at 60 seconds |
| --- | ---: | --- |
| Halving | 432,000 blocks | 300 days |
| Storage rent age | 1,051,200 blocks since box creation | 730 days |
| First Autolykos dataset increase | Height 614,400 | 426 days 15 hours 59 minutes after height-one genesis |
| Further Autolykos increase | 51,200 blocks | 35 days 13 hours 20 minutes |
| Autolykos growth cap | Height 4,198,400 | 2,915 days 13 hours 19 minutes after height-one genesis |
| Voting epoch | 128 blocks | 2 hours 8 minutes |
| Soft-fork voting window | 8 voting epochs | 17 hours 4 minutes |
| Post-vote activation window | 8 activation epochs plus the next epoch | 19 hours 12 minutes |
| Devnet miner reward maturity | 3 blocks | 3 minutes |
| Public testnet miner reward maturity | 5 blocks | 5 minutes |
| Unlaunched mainnet template miner maturity | 1,440 blocks | 1 day |

The storage threshold therefore represents two nominal 365-day years at the
current target, rather than the inherited four-year description. Both native
storage-age constants remain 1,051,200. Spending eligibility is checked against
actual heights, not dates. Changing rent age or costs requires a separate
consensus decision, script/interpreter tests and an announced activation.

Autolykos keeps its original height-dependent table growth and integer rounding.
At each growth boundary, `N` becomes `floor(N / 100) * 105`; it starts at 67,108,864
and reaches 2,143,944,600 at the cap. Growth becomes twice as frequent in nominal
calendar time at 60 seconds. Doubling its height thresholds would change PoW and
break compatibility with miners that retain the existing schedule. No such
change is applied here. Element counts do not predict a particular GPU's usable
memory, implementation overhead or mining performance.

Voting windows govern the existing parameter/voting machinery. They do not
activate the optional DAA configuration automatically. Reward maturity and pool
payment confirmation depth are separate: a matured coinbase does not itself
prove a payment transaction has enough confirmations.

Emission remains integer based. Native transaction and script tests exercise
both ends of all 37 nonzero reward epochs, conservation of every nanoZYRX, zero
founder outputs in the final small epochs and termination of the emission box.
The exact supply remains 86,399,999.99352 ZYRX; the theoretical geometric maximum
is 86,400,000. Neither the DAA hook nor this audit changes those rules.

Late emission outputs can be below the normal `minValuePerByte` floor. Existing
native transaction validation permits positive dust-sized outputs only when a
single input spends the exact network emission script; that script still enforces
the reward, recipients and conservation. Regression tests exercise this full
native validation path and reject the same dust-sized outputs for an ordinary
input. Spending or transferring tiny rewards later remains subject to the normal
output floor and fees, so they may need aggregation with other wallet inputs.

Run the calendar and DAA verification with `scripts/daa-check`. The calendar JSON
reads the source network profiles and uses native constants and `calcN`; generated
reports stay in ignored local storage. No rent, GPU growth, voting, reward
maturity, genesis, live-network configuration or mainnet launch is changed.
