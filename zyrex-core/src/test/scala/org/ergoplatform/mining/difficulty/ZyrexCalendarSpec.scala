package org.ergoplatform.mining.difficulty

import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCorePropertyTest

class ZyrexCalendarSpec extends ErgoCorePropertyTest {
  property("both native storage-age constants retain the legacy block threshold") {
    Constants.StoragePeriod shouldBe 1051200
    org.ergoplatform.wallet.protocol.Constants.StoragePeriod shouldBe Constants.StoragePeriod
    Constants.StoragePeriod.toLong * 60 shouldBe 730L * 86400
  }

  property("the GPU-compatible Autolykos growth schedule is unchanged at every boundary") {
    val pow = new org.ergoplatform.mining.AutolykosPowScheme(32, 26)
    pow.IncreaseStart shouldBe 614400
    pow.IncreasePeriodForN shouldBe 51200
    pow.NIncreasementHeightMax shouldBe 4198400
    pow.calcN(4.toByte, pow.IncreaseStart - 1) shouldBe pow.NBase
    pow.calcN(4.toByte, pow.IncreaseStart) shouldBe pow.NBase / 100 * 105
    (pow.IncreaseStart to pow.NIncreasementHeightMax by pow.IncreasePeriodForN).foreach { height =>
      pow.calcN(4.toByte, height) shouldBe pow.calcN(4.toByte, height - 1) / 100 * 105
    }
    pow.calcN(4.toByte, pow.NIncreasementHeightMax) shouldBe 2143944600
    pow.calcN(4.toByte, Int.MaxValue) shouldBe pow.calcN(4.toByte, pow.NIncreasementHeightMax)
  }

  property("calendar evidence is explicit about independent consensus schedules and disabled activation") {
    val report = ZyrexCalendarAudit.report.hcursor
    report.get[Boolean]("liveConsensusChanged").right.get shouldBe false
    val networks = report.get[Vector[io.circe.Json]]("networks").right.get
    networks.size shouldBe 3
    networks.foreach(_.hcursor.get[Boolean]("daaActivationConfigured").right.get shouldBe false)
    networks.foreach(_.hcursor.get[Boolean]("daaShorterEpochConfigured").right.get shouldBe false)
    networks.foreach(_.hcursor.get[Long]("targetSeconds").right.get shouldBe 60L)
  }
}
