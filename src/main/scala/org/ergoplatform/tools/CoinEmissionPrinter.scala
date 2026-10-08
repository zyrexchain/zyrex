package org.ergoplatform.tools

import org.ergoplatform.mining.emission.ZyrexEmissionRules
import org.ergoplatform.settings.{Args, ErgoSettingsReader, NetworkType}

object CoinEmissionPrinter extends App {
  val rules = ErgoSettingsReader.read(Args(networkTypeOpt = Some(NetworkType.DevNet)))
    .chainSettings.emissionRules
  println(s"Zyrex (ZYRX): ${rules.coinsTotal} nanoZYRX total; ${rules.blocksTotal} subsidy blocks")
  println("first height,last height,subsidy nanoZYRX,miner nanoZYRX,each founder nanoZYRX")
  ZyrexEmissionRules.Rates.indices.foreach { epoch =>
    val first = epoch * ZyrexEmissionRules.HalvingInterval + 1
    val last = (epoch + 1) * ZyrexEmissionRules.HalvingInterval
    val subsidy = rules.emissionAtHeight(first)
    val miner = rules.minersRewardAtHeight(first)
    println(s"$first,$last,$subsidy,$miner,${subsidy / 20}")
  }
}
