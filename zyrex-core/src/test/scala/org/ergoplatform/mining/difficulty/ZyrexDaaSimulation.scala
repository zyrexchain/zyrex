package org.ergoplatform.mining.difficulty

import com.typesafe.config.ConfigFactory
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.utils.ErgoCoreTestConstants
import scala.collection.mutable
import scala.concurrent.duration.DurationLong

/** Deterministic expected-time model that calls the actual consensus calculator. */
object ZyrexDaaSimulation {
  val WarmupEpochs: Int = 10
  val StressEpochs: Int = 32
  val ReferenceDifficulty: BigInt = BigInt(1) << 32
  val root: Path = Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(path => Files.exists(path.resolve("src/main/resources/testnet.conf")))
    .getOrElse(throw new IllegalStateException("Run from the project checkout"))

  final case class Profile(
      name: String,
      epochLength: Int,
      useLastEpochs: Int,
      intervalMillis: Long,
      initialDifficultyHex: String
  ) {
    def calculator: DifficultyAdjustment = new DifficultyAdjustment(
      ErgoCoreTestConstants.chainSettings.copy(
        epochLength = epochLength,
        useLastEpochs = useLastEpochs,
        blockInterval = intervalMillis.millis,
        initialDifficultyHex = initialDifficultyHex
      )
    )
  }

  final case class Scenario(name: String, rate: Int => (Int, Int), drift: Int => Long)

  final case class Epoch(
      number: Int,
      elapsedMillis: Long,
      endHeight: Int,
      difficulty: BigInt,
      nextDifficulty: BigInt
  ) {
    def json: Json = Json.obj(
      "stressEpoch" -> Json.fromInt(number),
      "elapsedMillis" -> Json.fromLong(elapsedMillis),
      "endHeight" -> Json.fromInt(endHeight),
      "difficulty" -> Json.fromString(difficulty.toString),
      "nextDifficulty" -> Json.fromString(nextDifficulty.toString)
    )
  }

  final case class Result(
      profile: Profile,
      scenario: String,
      startingDifficulty: BigInt,
      epochs: Vector[Epoch],
      eip37: Boolean,
      positiveFloor: Boolean
  ) {
    def recoveryEpoch: Option[Int] = epochs.sliding(3).find { window =>
      val target = profile.epochLength.toLong * profile.intervalMillis
      window.size == 3 && window.forall { epoch =>
        epoch.elapsedMillis >= target * 9 / 10 && epoch.elapsedMillis <= target * 11 / 10
      }
    }.map(_.last.number)

    def json: Json = Json.obj(
      "profile" -> Json.fromString(profile.name),
      "scenario" -> Json.fromString(scenario),
      "algorithm" -> Json.fromString(
        if (positiveFloor) "eip37-positive-floor-candidate"
        else if (eip37) "eip37-candidate"
        else "legacy-live"),
      "epochLength" -> Json.fromInt(profile.epochLength),
      "useLastEpochs" -> Json.fromInt(profile.useLastEpochs),
      "targetIntervalMillis" -> Json.fromLong(profile.intervalMillis),
      "initialDifficultyHex" -> Json.fromString(profile.initialDifficultyHex),
      "startingDifficulty" -> Json.fromString(startingDifficulty.toString),
      "threeEpochRecoveryEnd" -> recoveryEpoch.fold(Json.Null)(Json.fromInt),
      "threeEpochRecoveryElapsedMillis" -> recoveryEpoch.fold(Json.Null) { number =>
        Json.fromLong(epochs.take(number).map(_.elapsedMillis).sum)
      },
      "epochs" -> Json.arr(epochs.map(_.json): _*)
    )
  }

  lazy val baseHeader: Header = {
    val path = root.resolve("src/main/resources/genesis/testnet.json")
    val raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    parse(raw).right.get.hcursor.downField("header").as[Header].right.get
      .copy(timestamp = 0L, height = 1)
  }

  def profiles: Vector[Profile] = Vector("devnet", "testnet", "mainnet").map { name =>
    val path = root.resolve(s"src/main/resources/$name.conf").toFile
    val config = ConfigFactory.parseFile(path).getConfig("zyrex.chain")
    Profile(
      name,
      config.getInt("epochLength"),
      config.getInt("useLastEpochs"),
      config.getDuration("blockInterval", TimeUnit.MILLISECONDS),
      config.getString("initialDifficultyHex")
    )
  }

  val scenarios: Vector[Scenario] = Vector(
    Scenario("constant", _ => (1, 1), _ => 0L),
    Scenario("hashrate_x4", _ => (4, 1), _ => 0L),
    Scenario("departure_90_percent", _ => (1, 10), _ => 0L),
    Scenario("departure_99_percent", _ => (1, 100), _ => 0L),
    Scenario("oscillating_x4_x0.25",
      epoch => if ((epoch / 2) % 2 == 0) (4, 1) else (1, 4), _ => 0L),
    Scenario("timestamps_future_boundary", _ => (1, 1),
      epoch => if (epoch < 2) 600000L else 0L),
    Scenario("timestamps_bunched_past", _ => (1, 1),
      epoch => if (epoch < 2) -600000L else 0L)
  )

  /** Match the legacy branch; omit the nonexistent height-zero header. */
  def nextDifficulty(
      control: DifficultyAdjustment,
      parent: Header,
      epochs: collection.Map[Int, Header],
      eip37: Boolean = false,
      positiveFloor: Boolean = false
  ): BigInt = {
    if (parent.height % control.chainSettings.epochLength != 0) parent.requiredDifficulty
    else {
      val length = control.chainSettings.epochLength
      val required = control.previousHeightsRequiredForRecalculation(
        parent.height + 1, length)
      val headers = required.flatMap(epochs.get)
      val calculated = if (eip37 && headers.size >= 2) {
        control.eip37Calculate(headers, length)
      } else control.calculate(headers, length)
      if (positiveFloor) calculated.max(BigInt(1)) else calculated
    }
  }

  def simulate(
      profile: Profile,
      scenario: Scenario,
      start: BigInt = ReferenceDifficulty,
      eip37: Boolean = false,
      positiveFloor: Boolean = false
  ): Result = {
    require(!positiveFloor || eip37, "The candidate floor applies only to EIP-37")
    val control = profile.calculator
    val endpoints = mutable.Map.empty[Int, Header]
    val rows = Vector.newBuilder[Epoch]
    var parent = baseHeader.copy(nBits = DifficultySerializer.encodeCompactBits(start))
    val baseline = parent.requiredDifficulty
    var now = 0L
    var epochStart = 0L
    var epochDifficulty = baseline
    val endHeight = profile.epochLength * (WarmupEpochs + StressEpochs)
    while (parent.height < endHeight) {
      val height = parent.height + 1
      val epochIndex = (height - 1) / profile.epochLength
      val stressIndex = epochIndex - WarmupEpochs
      val (numerator, denominator) =
        if (stressIndex < 0) (1, 1) else scenario.rate(stressIndex)
      val difficulty = nextDifficulty(control, parent, endpoints, eip37, positiveFloor)
      require(difficulty > 0,
        s"Native calculator returned non-positive difficulty at height $height")
      if ((height - 1) % profile.epochLength == 0) epochDifficulty = difficulty
      val divisor = baseline * numerator
      val dividend = difficulty * profile.intervalMillis * denominator
      val elapsed = ((dividend + divisor - 1) / divisor).max(1).toLong
      now = Math.addExact(now, elapsed)
      val drift = if (stressIndex < 0) 0L else scenario.drift(stressIndex)
      val timestamp = Math.max(parent.timestamp + 1, now + drift)
      require(timestamp > parent.timestamp, "Non-increasing simulated timestamp")
      require(timestamp - now <= profile.intervalMillis * 10,
        "Future timestamp exceeds node bound")
      parent = parent.copy(height = height, timestamp = timestamp,
        nBits = DifficultySerializer.encodeCompactBits(difficulty))
      if (height % profile.epochLength == 0) {
        endpoints(height) = parent
        val earliest = height - profile.epochLength * profile.useLastEpochs
        endpoints.keys.filter(_ < earliest).toVector.foreach(endpoints.remove)
        if (stressIndex >= 0) {
          rows += Epoch(stressIndex + 1, now - epochStart, height, epochDifficulty,
            nextDifficulty(control, parent, endpoints, eip37, positiveFloor))
        }
        epochStart = now
      }
    }
    Result(profile, scenario.name, baseline, rows.result(), eip37, positiveFloor)
  }

  def sourceHash(path: String): String = {
    val hash = MessageDigest.getInstance("SHA-256")
      .digest(Files.readAllBytes(root.resolve(path)))
    hash.map(byte => f"${byte & 0xff}%02x").mkString
  }

  def report: Json = {
    val coreSource = "zyrex-core/src/main/scala/org/ergoplatform/mining/difficulty/"
    val coreTests = "zyrex-core/src/test/scala/org/ergoplatform/mining/difficulty/"
    val headerSource = "src/main/scala/org/ergoplatform/nodeView/history/"
    val sources = Vector(
      coreSource + "DifficultyAdjustment.scala",
      coreSource + "DifficultySerializer.scala",
      headerSource + "storage/modifierprocessors/HeadersProcessor.scala",
      coreTests + "ZyrexDaaSimulation.scala",
      "src/main/resources/devnet.conf",
      "src/main/resources/testnet.conf",
      "src/main/resources/mainnet.conf"
    )
    val results = Vector(false, true).flatMap { eip37 =>
      profiles.flatMap { profile =>
        scenarios.map(scenario => simulate(profile, scenario, eip37 = eip37))
      }
    }
    val initialProfiles = profiles.filter(_.name != "testnet")
    val departure = scenarios.find(_.name == "departure_99_percent").get
    val initialDifficultyRuns = initialProfiles.map { profile =>
      simulate(profile, departure, BigInt(profile.initialDifficultyHex, 16))
    }
    val guardedResults = profiles.flatMap { profile =>
      scenarios.map(scenario => simulate(profile, scenario,
        eip37 = true, positiveFloor = true))
    }
    val guardedInitialRuns = initialProfiles.map { profile =>
      simulate(profile, departure, BigInt(profile.initialDifficultyHex, 16),
        eip37 = true, positiveFloor = true)
    }
    val failures = initialProfiles.map { profile =>
      val error = scala.util.Try(
        simulate(profile, departure,
          BigInt(profile.initialDifficultyHex, 16), eip37 = true)
      ).failed.get
      Json.obj(
        "profile" -> Json.fromString(profile.name),
        "algorithm" -> Json.fromString("eip37-candidate"),
        "scenario" -> Json.fromString(departure.name),
        "startingDifficulty" -> Json.fromString(
          BigInt(profile.initialDifficultyHex, 16).toString),
        "error" -> Json.fromString(error.getMessage)
      )
    }
    Json.obj(
      "calculator" -> Json.fromString(
        "native calculate, native eip37Calculate, and candidate normalized-result floor"),
      "model" -> Json.fromString("deterministic expected block time; " +
        "integer millisecond ceiling; no stochastic PoW or network"),
      "warmupEpochs" -> Json.fromInt(WarmupEpochs),
      "stressEpochs" -> Json.fromInt(StressEpochs),
      "sourceSha256" -> Json.obj(
        sources.map(path => path -> Json.fromString(sourceHash(path))): _*),
      "results" -> Json.arr(
        (results ++ initialDifficultyRuns ++ guardedResults ++ guardedInitialRuns)
          .map(_.json): _*),
      "failedCandidateRuns" -> Json.arr(failures: _*)
    )
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Pass an ignored output JSON path")
    val output = root.resolve(args(0)).normalize()
    require(output.startsWith(root.resolve(".local")),
      "Write execution evidence only under ignored .local storage")
    Files.createDirectories(output.getParent)
    Files.write(output, (report.spaces2 + "\n").getBytes(StandardCharsets.UTF_8))
    println(s"Native DAA scenarios written to ${args(0)}")
  }
}
