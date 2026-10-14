package org.ergoplatform.mining.difficulty

import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.nodeView.history.ErgoHistoryUtils.{Difficulty, Height}
import org.ergoplatform.settings.ChainSettings
import scorex.util.ScorexLogging
import scala.concurrent.duration.FiniteDuration


class DifficultyAdjustment(val chainSettings: ChainSettings) extends ScorexLogging {

  import DifficultyAdjustment._

  val desiredInterval: FiniteDuration = chainSettings.blockInterval
  val useLastEpochs: Int = chainSettings.useLastEpochs
  val initialDifficulty: BigInt = chainSettings.initialDifficulty

  require(useLastEpochs > 1, "useLastEpochs should always be > 1")
  require(chainSettings.epochLength > 0, "diff epoch length should always be > 0")
  require(chainSettings.epochLength < Int.MaxValue / useLastEpochs, s"diff epoch length is too high for $useLastEpochs epochs")

  /**
    * @param height - height of a block
    * @param epochLength - difficulty readjustment epoch length
    * @return - height when difficulty will be recalculated after `height`
    */
  def nextRecalculationHeight(height: Height, epochLength: Int): Height = {
    if (height % epochLength == 0) {
      height + 1
    } else {
      (height / epochLength + 1) * epochLength + 1
    }
  }

  /**
    * @return heights of previous headers required for block recalculation
    */
  def previousHeightsRequiredForRecalculation(height: Height, epochLength: Int): Seq[Height] = {
    if ((height - 1) % epochLength == 0 && epochLength > 1) {
      (0 to useLastEpochs).map(i => (height - 1) - i * epochLength).filter(_ >= 0).reverse
    } else if ((height - 1) % epochLength == 0 && height > epochLength * useLastEpochs) {
      (0 to useLastEpochs).map(i => (height - 1) - i * epochLength).reverse
    } else {
      Seq(height - 1)
    }
  }

  /**
    * @param height - height of a block
    * @param epochLength - difficulty readjustment epoch length
    * @return - heights needed to calculate difficulty after `height`
    */
  def heightsForNextRecalculation(height: Height, epochLength: Int): Seq[Height] = {
    val activation = chainSettings.zyrex.flatMap(_.daaActivationHeight)
    val shorter = chainSettings.zyrex.flatMap(_.daaActivationEpochLength)
    (activation, shorter) match {
      case (Some(at), Some(length)) if height >= at - 1 =>
        val next = nextRecalculationHeight(height, length)
        postActivationHeights(next - 1, at, length)
      case (Some(at), Some(_)) if nextRecalculationHeight(height, epochLength) >= at => Seq(at - 1)
      case _ => previousHeightsRequiredForRecalculation(nextRecalculationHeight(height, epochLength), epochLength)
    }
  }

  /** Existing callers, including proof construction, share the activated anchor. */
  def zyrexEpochAt(height: Height): Option[Int] = chainSettings.zyrex.flatMap { settings =>
    settings.daaActivationHeight.filter(height >= _)
      .map(_ => settings.daaActivationEpochLength.getOrElse(chainSettings.epochLength))
  }

  /** A shortened period starts with one old-boundary anchor; prior intervals never enter its arithmetic. */
  def postActivationHeights(parentHeight: Height, activationHeight: Height, epochLength: Int): Seq[Height] = {
    val anchor = activationHeight - 1
    require(epochLength > 1 && anchor > 0 && parentHeight >= anchor,
      "Activated endpoints must follow the existing positive boundary")
    if ((parentHeight - anchor) % epochLength != 0) Seq.empty
    else {
      val earliest = (parentHeight.toLong - epochLength.toLong * useLastEpochs).max(anchor.toLong).toInt
      (earliest to parentHeight by epochLength)
    }
  }

  def zyrexRecalculationHeights(parentHeight: Height): Seq[Height] = {
    zyrexEpochAt(parentHeight + 1).toSeq.flatMap { length =>
      val settings = chainSettings.zyrex.get
      if (settings.daaActivationEpochLength.nonEmpty) {
        postActivationHeights(parentHeight, settings.daaActivationHeight.get, length)
      } else if (parentHeight % length == 0) {
        previousHeightsRequiredForRecalculation(parentHeight + 1, length).filter(_ > 0)
      } else Seq.empty
    }
  }

  /** @param previousHeaders  should be last headers of the previous epochs */
  def bitcoinCalculate(previousHeaders: Seq[Header], epochLength: Int): Difficulty = {
    val hs = previousHeaders.takeRight(2)
    bitcoinCalculate(hs(0), hs(1), epochLength: Int)
  }

  /**
    * Calculate difficulty as done in Bitcoin (with no capping result)
    *
    * Please note this method does not normalize its result!
    *
    * @param start - last block of previous epoch
    * @param end - last block of current epoch
    */
  private def bitcoinCalculate(start: Header, end: Header, epochLength: Int): BigInt = {
    end.requiredDifficulty * desiredInterval.toMillis * epochLength / (end.timestamp - start.timestamp)
  }

  /**
    * Calculate difficulty for first block of a new epoch according to EIP-37
    * @param previousHeaders - last headers of the configured number of recent epochs
    * @param epochLength - epoch length
    */
  def eip37Calculate(previousHeaders: Seq[Header], epochLength: Int): Difficulty = {
    require(previousHeaders.size >= 2, s"at least two headers needed for diff recalc, have $previousHeaders")
    val lastDiff = previousHeaders.last.requiredDifficulty

    val predictiveDiff = calculate(previousHeaders, epochLength)
    val limitedPredictiveDiff = if (predictiveDiff > lastDiff) {
      predictiveDiff.min(lastDiff * 3 / 2)
    } else {
      predictiveDiff.max(lastDiff / 2)
    }
    val classicDiff = bitcoinCalculate(previousHeaders, epochLength)
    val avg = (classicDiff + limitedPredictiveDiff) / 2
    val uncompressedDiff = if (avg > lastDiff) {
      avg.min(lastDiff * 3 / 2)
    } else {
      avg.max(lastDiff / 2)
    }
    log.debug(s"Difficulty for ${previousHeaders.last.height + 1}: predictive $predictiveDiff, " +
      s"limited predictive: $limitedPredictiveDiff, classic: $classicDiff, resulting uncompressed: $uncompressedDiff")
    // perform serialization cycle in order to normalize resulted difficulty
    DifficultySerializer.decodeCompactBits(
      DifficultySerializer.encodeCompactBits(uncompressedDiff)
    )
  }

  /** Zyrex opt-in rule: positive compact difficulty, strict existing epoch endpoints. */
  def guardedEip37Calculate(previousHeaders: Seq[Header], epochLength: Int): Difficulty = {
    require(epochLength > 1 && previousHeaders.nonEmpty, "Existing epoch endpoints are required")
    previousHeaders.foreach { header =>
      require(header.height > 0 && header.requiredDifficulty > 0, "Endpoint difficulty must be positive")
    }
    previousHeaders.sliding(2).filter(_.size == 2).foreach { endpoints =>
      require(endpoints.last.height - endpoints.head.height == epochLength, "Incorrect endpoint height interval")
      require(endpoints.last.timestamp > endpoints.head.timestamp, "Endpoint timestamps must strictly increase")
    }
    val calculated = if (previousHeaders.size == 1) previousHeaders.head.requiredDifficulty
      else eip37Calculate(previousHeaders, epochLength)
    // Native floor(1 * 3 / 2) is one: allow the smallest integer increase only when the classic estimate demands it.
    val bootstrapIncrease = previousHeaders.size >= 2 && previousHeaders.last.requiredDifficulty == 1 &&
      bitcoinCalculate(previousHeaders, epochLength) > 1
    val positive = if (bootstrapIncrease) BigInt(2) else calculated.max(BigInt(1))
    DifficultySerializer.decodeCompactBits(DifficultySerializer.encodeCompactBits(positive)).max(BigInt(1))
  }

  @SuppressWarnings(Array("TraversableHead"))
  def calculate(previousHeaders: Seq[Header], epochLength: Int): Difficulty = {
    require(previousHeaders.nonEmpty, "PreviousHeaders should always contain at least 1 element")

    val uncompressedDiff = {
      if (previousHeaders.lengthCompare(1) == 0 || previousHeaders.head.timestamp >= previousHeaders.last.timestamp) {
        previousHeaders.head.requiredDifficulty
      } else {
        val data: Seq[(Int, Difficulty)] = previousHeaders.sliding(2).toList.map { d =>
          val start = d.head
          val end = d.last
          require(end.height - start.height == epochLength, s"Incorrect heights interval for $d")
          val diff = end.requiredDifficulty * desiredInterval.toMillis * epochLength / (end.timestamp - start.timestamp)
          (end.height, diff)
        }
        val diff = interpolate(data, epochLength)
        if (diff >= 1) diff else initialDifficulty
      }
    }
    // perform serialization cycle in order to normalize resulted difficulty
    DifficultySerializer.decodeCompactBits(
      DifficultySerializer.encodeCompactBits(uncompressedDiff)
    )
  }

  //y = a + bx
  private[difficulty] def interpolate(data: Seq[(Int, Difficulty)], epochLength: Int): Difficulty = {
    val size = data.size
    if (size == 1) {
      data.head._2
    } else {
      val xy: Iterable[BigInt] = data.map(d => d._1 * d._2)
      val x: Iterable[BigInt] = data.map(d => BigInt(d._1))
      val x2: Iterable[BigInt] = data.map(d => BigInt(d._1) * d._1)
      val y: Iterable[BigInt] = data.map(d => d._2)
      val xySum = xy.sum
      val x2Sum = x2.sum
      val ySum = y.sum
      val xSum = x.sum

      val b: BigInt = (xySum * size - xSum * ySum) * PrecisionConstant / (x2Sum * size - xSum * xSum)
      val a: BigInt = (ySum * PrecisionConstant - b * xSum) / size / PrecisionConstant

      val point = data.map(_._1).max + epochLength
      a + b * point / PrecisionConstant
    }
  }

}

object DifficultyAdjustment {
  val PrecisionConstant: Int = 1000000000
}
