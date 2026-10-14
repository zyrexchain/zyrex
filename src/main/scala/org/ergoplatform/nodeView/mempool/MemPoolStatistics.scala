package org.ergoplatform.nodeView.mempool

import org.ergoplatform.nodeView.mempool.OrderedTxPool.WeightedTxId


/**
  * Immutable implementation of mempool statistics
  *
  * @param startMeasurement - start of measurement interval
  * @param takenTxns        - amount of taken transaction since start of measurement
  * @param snapTime         - last snapshot time
  * @param snapTakenTxns    - amount of transaction at the moment of last snapshot
  */
case class MemPoolStatistics(startMeasurement: Long,
                             takenTxns: Long = 0,
                             snapTime: Long,
                             snapTakenTxns: Long = 0,
                             histogram: List[FeeHistogramBin] = MemPoolStatistics.defaultPoolHistogram,
                             confirmedFees: Vector[ConfirmedFeeSample] = Vector.empty) {

  def addConfirmed(sample: ConfirmedFeeSample, now: Long): MemPoolStatistics = {
    val retained = FeeEstimation.fresh(confirmedFees, now).filterNot(_.id == sample.id)
    copy(confirmedFees = FeeEstimation.fresh(retained :+ sample, now))
  }

  def withoutConfirmations(ids: Set[scorex.util.ModifierId]): MemPoolStatistics =
    copy(confirmedFees = confirmedFees.filterNot(sample => ids.contains(sample.id)))

  /**
    * Add new entry to mempool statistics. This method is called when transaction is taken from mempool
    * and placed in blockchain. It is not called when transaction in thrown away from the pool by replacement policy.
    * To make statistic better represent most recent system behaviour, we periodically (each measurementIntervalMsec)
    * prune statistic. To avoid siutation when we do not have statistic at all, we actually keep data up to
    * 2*measurementIntervalMsec and periodically cut half of range.
    */
  def add(currTime: Long, wtx: WeightedTxId): MemPoolStatistics = {
    val curTakenTx = takenTxns + 1
    val (newTakenTx, newMeasurement, newSnapTxs, newSnapTime) =
      if (currTime - snapTime > MemPoolStatistics.measurementIntervalMsec) {
        if (snapTakenTxns != 0) {
          (curTakenTx - snapTakenTxns, snapTime, curTakenTx, currTime)
        } else {
          (curTakenTx, startMeasurement, curTakenTx, currTime)
        }
      } else {
        (curTakenTx, startMeasurement, snapTakenTxns, snapTime)
      }
    val durationMinutes = ((currTime - wtx.created) / (60 * 1000)).toInt
    val newHist =
      if (durationMinutes >= 0 && durationMinutes < MemPoolStatistics.nHistogramBins) {
        val histx = math.min(Int.MaxValue.toLong, histogram(durationMinutes).nTxns.toLong + 1).toInt
        val hisfee = FeeEstimation.boundedLong(BigInt(histogram(durationMinutes).totalFee) + wtx.feePerFactor)
        histogram.updated(durationMinutes, FeeHistogramBin(histx, hisfee))
      } else {
        histogram
      }
    copy(startMeasurement = newMeasurement, takenTxns = newTakenTx, snapTime = newSnapTime,
      snapTakenTxns = newSnapTxs, histogram = newHist)
  }
}

object MemPoolStatistics {
  // Time parameters of mempool statistics
  val nHistogramBins: Int = 60  /* one hour */
  val measurementIntervalMsec: Int = 60 * 1000 /* one minute */

  val defaultPoolHistogram: List[FeeHistogramBin] = List.fill(MemPoolStatistics.nHistogramBins)(FeeHistogramBin(0, 0))
}
