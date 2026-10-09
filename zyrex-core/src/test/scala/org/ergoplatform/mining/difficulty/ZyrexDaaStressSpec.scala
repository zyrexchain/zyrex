package org.ergoplatform.mining.difficulty

import org.ergoplatform.utils.ErgoCorePropertyTest
import scala.util.Try

/** Stress evidence for unchanged live consensus, not a network-readiness assertion. */
class ZyrexDaaStressSpec extends ErgoCorePropertyTest {
  import ZyrexDaaSimulation._

  property("source profiles distinguish private, public and mainnet-template epochs") {
    val actual = profiles.map { profile =>
      (profile.name, profile.epochLength, profile.useLastEpochs, profile.intervalMillis)
    }
    actual shouldBe Vector(
      ("devnet", 16, 8, 60000L),
      ("testnet", 64, 8, 60000L),
      ("mainnet", 2048, 8, 60000L)
    )
  }

  property("constant expected hashrate preserves native compact difficulty") {
    profiles.foreach { profile =>
      val result = simulate(profile, scenarios.head)
      result.epochs.size shouldBe StressEpochs
      result.epochs.foreach { epoch =>
        epoch.elapsedMillis shouldBe profile.epochLength.toLong * profile.intervalMillis
        epoch.difficulty shouldBe ReferenceDifficulty
        epoch.nextDifficulty shouldBe ReferenceDifficulty
      }
    }
  }

  property("abrupt departures remain slow until the actual epoch boundary") {
    profiles.foreach { profile =>
      val cases = Seq("departure_90_percent" -> 10, "departure_99_percent" -> 100)
      cases.foreach { case (name, multiplier) =>
        val result = simulate(profile, scenarios.find(_.name == name).get)
        val expected = profile.epochLength.toLong * profile.intervalMillis * multiplier
        result.epochs.head.elapsedMillis shouldBe expected
        result.epochs.head.difficulty shouldBe ReferenceDifficulty
        result.epochs.head.nextDifficulty should be < ReferenceDifficulty
        result.epochs.foreach(_.nextDifficulty should be > BigInt(0))
      }
    }
  }

  property("native legacy result is not subject to the EIP-37 step limits") {
    profiles.foreach { profile =>
      val control = profile.calculator
      val length = profile.epochLength
      val headers = (0 to 8).map { number =>
        baseHeader.copy(
          height = (number + 1) * length,
          timestamp = number.toLong * length,
          nBits = DifficultySerializer.encodeCompactBits(ReferenceDifficulty)
        )
      }
      control.calculate(headers, length) shouldBe
        ReferenceDifficulty * profile.intervalMillis
      control.eip37Calculate(headers, length) shouldBe ReferenceDifficulty * 3 / 2
    }
  }

  property("timestamp fallback and invalid interior boundaries are explicit") {
    profiles.foreach { profile =>
      val control = profile.calculator
      val first = baseHeader.copy(
        height = profile.epochLength,
        timestamp = 1000L,
        nBits = DifficultySerializer.encodeCompactBits(ReferenceDifficulty)
      )
      val second = first.copy(height = first.height + profile.epochLength)
      val length = profile.epochLength
      control.calculate(Seq(first), length) shouldBe ReferenceDifficulty
      control.calculate(Seq(first, second), length) shouldBe ReferenceDifficulty
      control.calculate(Seq(first, second.copy(timestamp = 999L)), length) shouldBe
        ReferenceDifficulty
      val third = second.copy(height = second.height + length, timestamp = 2000L)
      Try(control.calculate(Seq(first, second, third), length)).isFailure shouldBe true
    }
  }

  property("step, oscillation and timestamp scenarios are deterministic") {
    profiles.foreach { profile =>
      scenarios.foreach { scenario =>
        val first = simulate(profile, scenario)
        simulate(profile, scenario) shouldBe first
        first.epochs.size shouldBe StressEpochs
        first.epochs.foreach { epoch =>
          epoch.elapsedMillis should be > 0L
          epoch.difficulty should be > BigInt(0)
          epoch.nextDifficulty should be > BigInt(0)
        }
      }
    }
  }

  property("difficulty-one bootstrap stays at its floor after 99-percent departure") {
    profiles.filter(_.initialDifficultyHex == "01").foreach { profile =>
      val scenario = scenarios.find(_.name == "departure_99_percent").get
      val result = simulate(profile, scenario, BigInt(1))
      result.epochs.foreach { epoch =>
        epoch.difficulty shouldBe BigInt(1)
        epoch.nextDifficulty shouldBe BigInt(1)
        val expected = profile.epochLength.toLong * profile.intervalMillis * 100
        epoch.elapsedMillis shouldBe expected
      }
      result.recoveryEpoch shouldBe None
    }
  }

  property("mature EIP-37 candidate applies its native normalized step bounds") {
    profiles.foreach { profile =>
      scenarios.foreach { scenario =>
        val result = simulate(profile, scenario, eip37 = true)
        result.epochs.foreach { epoch =>
          def normalized(value: BigInt): BigInt = DifficultySerializer.decodeCompactBits(
            DifficultySerializer.encodeCompactBits(value)
          )
          epoch.nextDifficulty should be >= normalized(epoch.difficulty / 2)
          epoch.nextDifficulty should be <= normalized(epoch.difficulty * 3 / 2)
          epoch.nextDifficulty should be > BigInt(0)
        }
      }
    }
  }

  property("native EIP-37 candidate needs a positive floor at difficulty one") {
    profiles.filter(_.initialDifficultyHex == "01").foreach { profile =>
      val headers = Seq(
        baseHeader.copy(height = profile.epochLength, timestamp = 0L,
          nBits = DifficultySerializer.encodeCompactBits(BigInt(1))),
        baseHeader.copy(height = profile.epochLength * 2,
          timestamp = profile.intervalMillis * profile.epochLength * 100,
          nBits = DifficultySerializer.encodeCompactBits(BigInt(1)))
      )
      profile.calculator.eip37Calculate(headers, profile.epochLength) shouldBe BigInt(0)
      val error = intercept[IllegalArgumentException] {
        simulate(profile, scenarios.find(_.name == "departure_99_percent").get,
          BigInt(1), eip37 = true)
      }
      error.getMessage should include("non-positive difficulty")
    }
  }

  property("guarded EIP-37 candidate stays positive for all profiles and scenarios") {
    profiles.foreach { profile =>
      scenarios.foreach { scenario =>
        val guarded = simulate(profile, scenario, eip37 = true, positiveFloor = true)
        val raw = simulate(profile, scenario, eip37 = true)
        guarded.epochs shouldBe raw.epochs
        guarded.epochs.foreach(_.nextDifficulty should be > BigInt(0))
      }
      val initial = simulate(profile,
        scenarios.find(_.name == "departure_99_percent").get,
        BigInt(1), eip37 = true, positiveFloor = true)
      initial.epochs.foreach { epoch =>
        epoch.difficulty shouldBe BigInt(1)
        epoch.nextDifficulty shouldBe BigInt(1)
      }
      initial.recoveryEpoch shouldBe None
    }
  }

  property("guarded native EIP-37 bounds hold for extreme-power integer vectors") {
    def normalized(value: BigInt): BigInt = DifficultySerializer.decodeCompactBits(
      DifficultySerializer.encodeCompactBits(value)
    )
    profiles.foreach { profile =>
      val control = profile.calculator
      val length = profile.epochLength
      Seq(BigInt(1), BigInt(2), BigInt(3), ReferenceDifficulty, BigInt(1) << 240)
        .foreach { requested =>
          val difficulty = normalized(requested)
          Seq(1L, profile.intervalMillis, profile.intervalMillis * 1000000L)
            .foreach { interval =>
              val first = baseHeader.copy(height = length, timestamp = 0L,
                nBits = DifficultySerializer.encodeCompactBits(difficulty))
              val last = first.copy(height = length * 2,
                timestamp = interval * length)
              val endpoints = Map(first.height -> first, last.height -> last)
              val guarded = nextDifficulty(control, last, endpoints,
                eip37 = true, positiveFloor = true)
              guarded should be > BigInt(0)
              guarded should be >= normalized(difficulty / 2).max(BigInt(1))
              guarded should be <= normalized(difficulty * 3 / 2).max(BigInt(1))
              normalized(guarded) shouldBe guarded
            }
        }
      val first = baseHeader.copy(height = length, timestamp = 1000L)
      val equal = first.copy(height = length * 2)
      val invalid = Map(first.height -> first, equal.height -> equal)
      Try(nextDifficulty(control, equal, invalid,
        eip37 = true, positiveFloor = true)).isFailure shouldBe true
    }
  }
}
