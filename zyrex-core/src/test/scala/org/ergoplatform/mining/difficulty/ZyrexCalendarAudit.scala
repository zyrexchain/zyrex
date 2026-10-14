package org.ergoplatform.mining.difficulty

import com.typesafe.config.ConfigFactory
import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.ergoplatform.settings.Constants

/** Audits native height schedules; wall-clock values assume continuous target-rate mining. */
object ZyrexCalendarAudit {
  import ZyrexDaaSimulation.{profiles, root}
  private val day = BigInt(86400)

  private def schedule(name: String, blocks: Long, intervalSeconds: Long): Json = Json.obj(
    "name" -> Json.fromString(name), "blocks" -> Json.fromLong(blocks),
    "nominalSeconds" -> Json.fromString((BigInt(blocks) * intervalSeconds).toString),
    "nominalDays" -> Json.fromString((BigDecimal(blocks) * intervalSeconds / BigDecimal(day)).bigDecimal.toPlainString)
  )

  def report: Json = {
    val pow = new org.ergoplatform.mining.AutolykosPowScheme(32, 26)
    val networks = profiles.map { profile =>
      val config = ConfigFactory.parseFile(root.resolve(s"src/main/resources/${profile.name}.conf").toFile)
        .getConfig("zyrex.chain")
      val seconds = profile.intervalMillis / 1000
      val voting = config.getLong("voting.votingLength")
      Json.obj("network" -> Json.fromString(profile.name),
        "targetSeconds" -> Json.fromLong(seconds),
        "daaActivationConfigured" -> Json.fromBoolean(!config.getIsNull("zyrex.daaActivationHeight")),
        "daaShorterEpochConfigured" -> Json.fromBoolean(!config.getIsNull("zyrex.daaActivationEpochLength")),
        "schedules" -> Json.arr(
          schedule("difficulty epoch", profile.epochLength, seconds),
          schedule("voting epoch", voting, seconds),
          schedule("soft fork voting window", voting * config.getLong("voting.softForkEpochs"), seconds),
          schedule("post-vote activation window including next epoch", voting * (config.getLong("voting.activationEpochs") + 1), seconds),
          schedule("miner reward maturity", config.getLong("monetary.minerRewardDelay"), seconds),
          schedule("halving interval", config.getLong("monetary.epochLength"), seconds),
          schedule("storage rent age threshold", Constants.StoragePeriod, seconds),
          schedule("first Autolykos growth from height-one genesis", pow.IncreaseStart.toLong - 1, seconds),
          schedule("Autolykos growth period", pow.IncreasePeriodForN, seconds),
          schedule("Autolykos growth cap from height-one genesis", pow.NIncreasementHeightMax.toLong - 1, seconds)))
    }
    Json.obj("liveConsensusChanged" -> Json.False,
      "calendarModel" -> Json.fromString("Target-rate intervals; actual block time and 365-day years are not guarantees"),
      "storagePeriodBlocks" -> Json.fromInt(Constants.StoragePeriod),
      "walletStoragePeriodBlocks" -> Json.fromInt(org.ergoplatform.wallet.protocol.Constants.StoragePeriod),
      "autolykosGrowthStartHeight" -> Json.fromInt(pow.IncreaseStart),
      "autolykosGrowthPeriodBlocks" -> Json.fromInt(pow.IncreasePeriodForN),
      "autolykosGrowthCapHeight" -> Json.fromInt(pow.NIncreasementHeightMax),
      "autolykosMaximumElements" -> Json.fromInt(pow.calcN(4.toByte, pow.NIncreasementHeightMax)),
      "sourceSha256" -> Json.obj(Seq(
        "zyrex-core/src/main/scala/org/ergoplatform/settings/Constants.scala",
        "zyrex-wallet/src/main/scala/org/ergoplatform/wallet/protocol/Constants.scala",
        "zyrex-wallet/src/main/scala/org/ergoplatform/wallet/interpreter/ErgoInterpreter.scala",
        "zyrex-core/src/main/scala/org/ergoplatform/mining/AutolykosPowScheme.scala",
        "zyrex-core/src/main/scala/org/ergoplatform/settings/ZyrexChainSettings.scala",
        "zyrex-core/src/main/scala/org/ergoplatform/settings/VotingSettings.scala",
        "zyrex-core/src/test/scala/org/ergoplatform/mining/difficulty/ZyrexCalendarAudit.scala"
      ).map(path => path -> Json.fromString(ZyrexDaaSimulation.sourceHash(path))): _*),
      "networks" -> Json.arr(networks: _*))
  }

  def main(args: Array[String]): Unit = {
    val output = Paths.get(args.headOption.getOrElse(".local/audit-daa/calendar.json")).toAbsolutePath
    Files.createDirectories(output.getParent)
    Files.write(output, (report.spaces2 + "\n").getBytes(StandardCharsets.UTF_8))
    println(s"Native calendar evidence saved to $output")
  }
}
