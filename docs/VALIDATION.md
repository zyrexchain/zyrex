# Private-testnet validation

Date: 2026-10-08. Base commit: `3a6b00d37e3bda2b36447a922606b4ca5a09568f`.

- Compile and assembly succeeded with JDK 11 and sbt 1.11.1.
- ZyrexConsensusSpec + MessageSerializerSpecification: 14 tests passed.
- Upstream AutolykosPowSchemeSpec + DifficultyAdjustmentSpecification: 18 tests passed.
- All 37 nonzero subsidy periods were checked at both boundaries by native
  transaction validation and direct guarding-script evaluation, including the final nano unit.
- Tampered or omitted founder outputs, wrong reward amounts and foreign address
  prefixes are rejected. P2PK, P2SH and P2S round trips and damaged checksums are tested.
- Network parser rejects foreign network magic, including other Zyrex networks.
- Two distinct founder keys are required; infinity and duplicate encodings are rejected.
- UTXO rollback removes orphan rewards and applies an alternate chain. Two
  independent state databases agree through height 129, including the first voting epoch.
- Three Docker nodes mined a shared chain and confirmed a CLI payment. Follower
  and miner restarts retained state and mining resumed. Detailed execution reports remain local.
- An isolated fourth node accepted a CPU-computed external RPC solution at
  height 2. CLI key derivation, lock/unlock and mnemonic restore were exercised.
  Detailed execution reports remain local.

- Pool: 27 tests passed for reference Autolykos verification, Stratum TCP,
  address isolation, duplicate/stale/invalid shares, exact PROP accounting,
  orphan work, signed-transaction recovery, and pinned mining-key preparation.
  GPU stale batches retain the connection; malformed-share bursts still disconnect.
  A solved candidate is not repeatedly submitted. Wallet maintenance is asynchronous
  to job delivery. Best-header/state readiness, static difficulty bounds and GPU
  evidence binding are tested.
- Two CPU Stratum clients mined canonical blocks in the existing private chain.
  Their payments were confirmed in both receiving wallets; block subsidy outputs
  remained 90/5/5. Pool and pool-node restarts retained shares and payment IDs,
  and the pinned mining key remained stable. Detailed execution reports remain local.

- NVIDIA GPU: official lolMiner 1.98a on an RTX 4070 Ti (12 GB, driver 580.95.05)
  submitted Autolykos v2 shares over the actual LAN route. Canonical block nonces
  match the recorded GPU submissions, subsidy outputs retain 90/5/5, and payouts
  are confirmed in the receiving wallet. Detailed execution reports remain local.
  The shortened private DAA epochs adapted from CPU to GPU mining without resetting
  genesis or history. Initial low difficulty caused many stale shares; the stale
  counter in the report is cumulative, including that startup period.

AMD, other NVIDIA models and other miner programs have not been verified.
The private prototype is not a
mainnet release. Full upstream test suites and public bootstrap integration are
not included in these validation claims.
