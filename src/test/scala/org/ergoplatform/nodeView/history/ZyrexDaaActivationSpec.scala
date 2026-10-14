package org.ergoplatform.nodeView.history

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import java.io.File
import java.nio.file.Files
import org.ergoplatform.mining.difficulty.DifficultySerializer
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.settings.{ErgoSettings, ErgoSettingsReader}
import org.ergoplatform.utils.ErgoCorePropertyTest
import scala.collection.JavaConverters._

/** Real native header validation and branch lookup; no node or operator data is used. */
class ZyrexDaaActivationSpec extends ErgoCorePropertyTest {
  import org.ergoplatform.utils.ErgoCoreTestConstants.defaultMinerSecret
  import org.ergoplatform.mining.difficulty.ZyrexDaaSimulation.baseHeader

  private val config = ConfigFactory.parseFile(new File("src/main/resources/devnet.conf"))
    .withFallback(ConfigFactory.parseFile(new File("src/main/resources/application.conf")))
    .withFallback(ConfigFactory.defaultReference()).resolve()
  private val original = ErgoSettingsReader.fromConfig(config)

  private def fixture(activation: Option[Int], initial: String = "08", shorter: Option[Int] = None): ErgoSettings = {
    val chain = original.chainSettings.copy(epochLength = 4, initialDifficultyHex = initial,
      genesisId = None, zyrex = Some(original.chainSettings.zyrex.get.copy(
        daaActivationHeight = activation, daaActivationEpochLength = shorter)))
    original.copy(chainSettings = chain, nodeSettings = original.nodeSettings.copy(
      verifyTransactions = false, mining = false, blocksToKeep = -1, extraIndex = false))
  }

  private def using(activation: Option[Int], initial: String = "08", shorter: Option[Int] = None)(test: ErgoHistory => Unit): Unit = {
    val folder = Files.createTempDirectory("zyrex-daa-header-")
    val settings = fixture(activation, initial, shorter).copy(directory = folder.toString)
    val history = ErgoHistory.readOrGenerate(settings)(null)
    try test(history)
    finally {
      history.closeStorage()
      val files = Files.walk(folder)
      try files.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(path => Files.delete(path))
      finally files.close()
    }
  }

  private def prove(parent: Option[Header], difficulty: BigInt, timestamp: Long): Header = {
    original.chainSettings.powScheme.prove(parent, 4.toByte,
      DifficultySerializer.encodeCompactBits(difficulty), baseHeader.stateRoot,
      baseHeader.ADProofsRoot, baseHeader.transactionsRoot, timestamp, baseHeader.extensionRoot,
      Array.fill(3)(0.toByte), defaultMinerSecret.w, 0, 100000).get
  }

  private def append(history: ErgoHistory, parent: Option[Header], timestamp: Long): Header = {
    val difficulty = parent.map(history.requiredDifficultyAfter).getOrElse(history.difficultyCalculator.initialDifficulty)
    val header = prove(parent, difficulty, timestamp)
    history.applicableTry(header).isSuccess shouldBe true
    history.append(header).isSuccess shouldBe true
    header
  }

  private def prefix(history: ErgoHistory, start: Long = System.currentTimeMillis() - 3600000L): Vector[Header] = {
    (1 to 8).foldLeft(Vector.empty[Header]) { (headers, height) =>
      val timestamp = if (height <= 4) start + height * 60000L else start + 240000L + (height - 4) * 30000L
      headers :+ append(history, headers.lastOption, timestamp)
    }
  }

  property("activation is disabled in all checked-in network profiles and does not change genesis commitments") {
    Seq("devnet", "testnet", "mainnet").foreach { name =>
      val network = ConfigFactory.parseFile(new File(s"src/main/resources/$name.conf"))
        .withFallback(ConfigFactory.parseFile(new File("src/main/resources/application.conf")))
        .withFallback(ConfigFactory.defaultReference()).resolve()
      network.getIsNull("zyrex.chain.zyrex.daaActivationHeight") shouldBe true
      network.getIsNull("zyrex.chain.zyrex.daaActivationEpochLength") shouldBe true
      // The unlaunched template has no founder keys; use explicitly test-only keys for reader checks.
      val readable = if (name == "mainnet") network.withValue("zyrex.chain.zyrex.founderPubkeys",
        ConfigValueFactory.fromIterable(java.util.Arrays.asList(original.chainSettings.zyrex.get.founderPubkeys: _*))) else network
      val chain = ErgoSettingsReader.fromConfig(readable).chainSettings
      chain.zyrex.get.daaActivationHeight shouldBe None
      chain.zyrex.get.daaActivationEpochLength shouldBe None
      ErgoSettingsReader.fromConfig(readable.withoutPath("zyrex.chain.zyrex.daaActivationHeight")
        .withoutPath("zyrex.chain.zyrex.daaActivationEpochLength"))
        .chainSettings.zyrex.get.daaActivationHeight shouldBe None
      val activation = chain.epochLength * 2 + 1
      val enabled = chain.copy(zyrex = Some(chain.zyrex.get.copy(
        daaActivationHeight = Some(activation), daaActivationEpochLength = Some(chain.epochLength / 2))))
      enabled.genesisId shouldBe chain.genesisId
      enabled.genesisStateDigest shouldBe chain.genesisStateDigest
      enabled.emissionProposition.bytes.toSeq shouldBe chain.emissionProposition.bytes.toSeq
    }
  }

  property("activation must follow genesis on the first header after the configured epoch") {
    Seq(0, 1, 2, 4, 6, 8, -1).foreach { height =>
      intercept[IllegalArgumentException](fixture(Some(height)))
    }
    fixture(Some(5)).chainSettings.zyrex.get.daaActivationHeight shouldBe Some(5)
    fixture(Some(9)).chainSettings.epochLength shouldBe 4
    val enabled = fixture(Some(9)).chainSettings
    enabled.copy(eip37EpochLength = Some(4)).epochLength shouldBe 4
    intercept[IllegalArgumentException](enabled.copy(eip37EpochLength = Some(128)))
    intercept[IllegalArgumentException](enabled.copy(epochLength = 1))
    intercept[IllegalArgumentException](fixture(None, shorter = Some(2)))
    Seq(0, 1, 3, 4, 8).foreach { length =>
      intercept[IllegalArgumentException](fixture(Some(9), shorter = Some(length)))
    }
    fixture(Some(9), shorter = Some(2)).chainSettings.epochLength shouldBe 4
  }

  property("native validation uses legacy before activation, guarded rules at the boundary and inherited difficulty after") {
    using(Some(9)) { active =>
      val headers = prefix(active)
      headers.last.requiredDifficulty shouldBe BigInt(8)
      active.requiredDifficultyAfter(headers.last) shouldBe BigInt(12)
      val legacy = prove(Some(headers.last), 16, headers.last.timestamp + 60000L)
      original.chainSettings.powScheme.validate(legacy).isSuccess shouldBe true
      active.applicableTry(legacy).isFailure shouldBe true
      val next = append(active, Some(headers.last), headers.last.timestamp + 60000L)
      next.requiredDifficulty shouldBe BigInt(12)
      active.requiredDifficultyAfter(next) shouldBe BigInt(12)
      append(active, Some(next), next.timestamp + 60000L).requiredDifficulty shouldBe BigInt(12)
    }
    using(None) { legacy =>
      val headers = prefix(legacy)
      legacy.requiredDifficultyAfter(headers.last) shouldBe BigInt(16)
      val changed = prove(Some(headers.last), 12, headers.last.timestamp + 60000L)
      original.chainSettings.powScheme.validate(changed).isSuccess shouldBe true
      legacy.applicableTry(changed).isFailure shouldBe true
    }
  }

  property("the first activated endpoint omits height zero and retains positive starting difficulty") {
    using(Some(5), "01") { history =>
      val headers = prefix(history)
      headers(4).requiredDifficulty shouldBe BigInt(1)
      history.requiredDifficultyAfter(headers(3)) shouldBe BigInt(1)
      history.difficultyCalculator.guardedEip37Calculate(Seq(headers(3)), 4) shouldBe BigInt(1)
    }
  }

  property("activated fork difficulty follows its own endpoints rather than the best chain") {
    using(Some(9)) { history =>
      val main = prefix(history)
      val ninth = append(history, Some(main.last), main.last.timestamp + 60000L)
      append(history, Some(ninth), ninth.timestamp + 60000L)
      val fork = (2 to 8).foldLeft(Vector(main.head)) { (headers, height) =>
        headers :+ append(history, headers.lastOption, main.head.timestamp + (height - 1) * 60000L + 1L)
      }
      history.isInBestChain(fork.last) shouldBe false
      history.requiredDifficultyAfter(fork.last) shouldBe BigInt(8)
      val wrong = prove(Some(fork.last), 12, fork.last.timestamp + 60000L)
      original.chainSettings.powScheme.validate(wrong).isSuccess shouldBe true
      history.applicableTry(wrong).isFailure shouldBe true
      append(history, Some(fork.last), fork.last.timestamp + 60000L).requiredDifficulty shouldBe BigInt(8)
    }
  }

  property("native activated headers retain positive targets after 90 and 99 percent power departure") {
    Seq("08", "01").foreach { initial =>
      Seq(10, 100).foreach { multiplier =>
        using(Some(9), initial) { history =>
          val warmup = prefix(history, System.currentTimeMillis() - 86400000L)
          val slowed = (1 to 4).foldLeft(warmup.last) { (parent, _) =>
            append(history, Some(parent), parent.timestamp + 60000L * multiplier)
          }
          val expected = if (initial == "01") BigInt(1) else BigInt(6)
          history.requiredDifficultyAfter(slowed) shouldBe expected
          val adjusted = append(history, Some(slowed), slowed.timestamp + 60000L * multiplier)
          adjusted.requiredDifficulty shouldBe expected
          adjusted.nBits shouldBe DifficultySerializer.encodeCompactBits(expected)
          original.chainSettings.powScheme.validate(adjusted).isSuccess shouldBe true
        }
      }
    }
  }

  property("a shorter activated epoch anchors the old boundary without mixing earlier time intervals") {
    using(Some(9), shorter = Some(2)) { history =>
      val warmup = prefix(history)
      val control = history.difficultyCalculator
      control.zyrexEpochAt(8) shouldBe None
      control.zyrexEpochAt(9) shouldBe Some(2)
      control.zyrexRecalculationHeights(8) shouldBe Seq(8)
      history.requiredDifficultyAfter(warmup.last) shouldBe BigInt(8)
      Seq(BigInt(12), BigInt(16)).foreach { wrong =>
        history.applicableTry(prove(Some(warmup.last), wrong, warmup.last.timestamp + 60000L)).isFailure shouldBe true
      }
      val ninth = append(history, Some(warmup.last), warmup.last.timestamp + 600000L)
      history.requiredDifficultyAfter(ninth) shouldBe BigInt(8)
      val tenth = append(history, Some(ninth), ninth.timestamp + 600000L)
      control.zyrexRecalculationHeights(10) shouldBe Seq(8, 10)
      control.heightsForNextRecalculation(8, 4) shouldBe Seq(8)
      control.heightsForNextRecalculation(9, 4) shouldBe Seq(8, 10)
      control.heightsForNextRecalculation(40, 4) shouldBe (24 to 40 by 2)
      history.requiredDifficultyAfter(tenth) shouldBe BigInt(4)
      val eleventh = append(history, Some(tenth), tenth.timestamp + 60000L)
      append(history, Some(eleventh), eleventh.timestamp + 60000L).requiredDifficulty shouldBe BigInt(4)
      val fork = (2 to 8).foldLeft(Vector(warmup.head)) { (headers, height) =>
        headers :+ append(history, headers.lastOption, warmup.head.timestamp + (height - 1) * 60000L + 1L)
      }
      val fork9 = append(history, Some(fork.last), fork.last.timestamp + 30000L)
      val fork10 = append(history, Some(fork9), fork9.timestamp + 30000L)
      history.isInBestChain(fork10) shouldBe false
      history.requiredDifficultyAfter(fork10) shouldBe BigInt(12)
      val wrongFork = prove(Some(fork10), 4, fork10.timestamp + 60000L)
      history.applicableTry(wrongFork).isFailure shouldBe true
      append(history, Some(fork10), fork10.timestamp + 60000L).requiredDifficulty shouldBe BigInt(12)
    }
  }

  property("native guarded startup escapes difficulty one only when faster endpoints justify the discrete increase") {
    using(Some(9), "01", Some(2)) { history =>
      val warmup = prefix(history)
      history.requiredDifficultyAfter(warmup.last) shouldBe BigInt(1)
      val ninth = append(history, Some(warmup.last), warmup.last.timestamp + 15000L)
      val tenth = append(history, Some(ninth), ninth.timestamp + 15000L)
      history.requiredDifficultyAfter(tenth) shouldBe BigInt(2)
      val trapped = prove(Some(tenth), 1, tenth.timestamp + 15000L)
      original.chainSettings.powScheme.validate(trapped).isSuccess shouldBe true
      history.applicableTry(trapped).isFailure shouldBe true
      val raised = append(history, Some(tenth), tenth.timestamp + 15000L)
      raised.requiredDifficulty shouldBe BigInt(2)
      raised.nBits shouldBe DifficultySerializer.encodeCompactBits(BigInt(2))
      val twelfth = append(history, Some(raised), raised.timestamp + 15000L)
      history.requiredDifficultyAfter(twelfth) shouldBe BigInt(3)
    }
  }

  property("native timestamp and nonpositive target validation remain mandatory after activation") {
    using(Some(9)) { history =>
      val headers = prefix(history)
      val next = append(history, Some(headers.last), headers.last.timestamp + 60000L)
      Seq(next.timestamp, System.currentTimeMillis() + 601000L).foreach { timestamp =>
        val invalid = prove(Some(next), next.requiredDifficulty, timestamp)
        original.chainSettings.powScheme.validate(invalid).isSuccess shouldBe true
        history.applicableTry(invalid).isFailure shouldBe true
      }
      val child = prove(Some(next), next.requiredDifficulty, next.timestamp + 60000L)
      Seq(0L, DifficultySerializer.encodeCompactBits(BigInt(-1))).foreach { bits =>
        history.applicableTry(child.copy(nBits = bits)).isFailure shouldBe true
      }
    }
  }
}
