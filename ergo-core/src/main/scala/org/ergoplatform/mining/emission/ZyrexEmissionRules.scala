package org.ergoplatform.mining.emission

import org.ergoplatform.settings.MonetarySettings

/** Heights 1..432000 receive the initial subsidy. Fees are separate from subsidy. */
class ZyrexEmissionRules(settings: MonetarySettings) extends EmissionRules(settings) {
  import ZyrexEmissionRules._

  override lazy val coinsTotal: Long = TotalSupply
  override lazy val blocksTotal: Int = LastEmissionHeight

  override val foundersCoinsTotal: Long = Rates.map(r => (r / 20) * 2 * HalvingInterval).sum
  override val minersCoinsTotal: Long = TotalSupply - foundersCoinsTotal

  override def emissionAtHeight(h: Long): Long =
    if (h <= 0 || h > LastEmissionHeight) 0L else Rates(((h - 1) / HalvingInterval).toInt)

  def founderRewardAtHeight(h: Long): Long = emissionAtHeight(h) / 20

  override def foundationRewardAtHeight(h: Long): Long = founderRewardAtHeight(h) * 2

  override def minersRewardAtHeight(h: Long): Long = emissionAtHeight(h) - foundationRewardAtHeight(h)

  override def issuedCoinsAfterHeight(h: Long): Long = {
    val capped = math.max(0L, math.min(h, LastEmissionHeight.toLong))
    val epochs = (capped / HalvingInterval).toInt
    Rates.take(epochs).sum * HalvingInterval +
      Rates.lift(epochs).getOrElse(0L) * (capped % HalvingInterval)
  }

  override def remainingFoundationRewardAtHeight(h: Long): Long = {
    val capped = math.max(0L, math.min(h, LastEmissionHeight.toLong))
    val epochs = (capped / HalvingInterval).toInt
    foundersCoinsTotal - Rates.take(epochs).map(r => r / 20 * 2).sum * HalvingInterval -
      Rates.lift(epochs).getOrElse(0L) / 20 * 2 * (capped % HalvingInterval)
  }
}

object ZyrexEmissionRules {
  val UnitsPerCoin: Long = 1000000000L
  val InitialReward: Long = 100L * UnitsPerCoin
  val HalvingInterval: Int = 432000
  val Rates: Vector[Long] = Iterator.iterate(InitialReward)(_ / 2).takeWhile(_ > 0).toVector
  val TotalSupply: Long = Rates.sum * HalvingInterval
  val LastEmissionHeight: Int = Rates.length * HalvingInterval
}
