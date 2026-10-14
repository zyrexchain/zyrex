package org.ergoplatform.nodeView.mempool

import io.circe.{Encoder, Json}
import scorex.util.ModifierId

/** A fee observation is recorded only when its transaction enters the active chain. */
case class ConfirmedFeeSample(id: ModifierId, confirmedAt: Long, fee: Long, size: Int)

case class FeeQuote(feeNano: Long,
                    minimumFeeNano: Long,
                    txSize: Int,
                    waitTime: Int,
                    basis: String,
                    sampleCount: Int,
                    mempoolBytes: Long,
                    maxTransactionSize: Int)

object FeeQuote {
  implicit val encoder: Encoder[FeeQuote] = Encoder.instance { quote =>
    Json.obj(
      "quoteVersion" -> Json.fromInt(1),
      "feeNano" -> Json.fromString(quote.feeNano.toString),
      "minimumFeeNano" -> Json.fromString(quote.minimumFeeNano.toString),
      "txSize" -> Json.fromInt(quote.txSize),
      "waitTime" -> Json.fromInt(quote.waitTime),
      "basis" -> Json.fromString(quote.basis),
      "sampleCount" -> Json.fromInt(quote.sampleCount),
      "mempoolBytes" -> Json.fromString(quote.mempoolBytes.toString),
      "historyWindowSeconds" -> Json.fromInt(FeeEstimation.HistoryWindowMillis / 1000),
      "maxTransactionSize" -> Json.fromInt(quote.maxTransactionSize)
    )
  }
}

/** Advisory byte-based policy; neither a consensus rule nor a confirmation guarantee. */
object FeeEstimation {
  val HistoryWindowMillis: Int = 30 * 60 * 1000
  val MaxSamples: Int = 512
  val MinimumSamples: Int = 3
  val MaxWaitMinutes: Int = 60

  def boundedLong(value: BigInt): Long = value.max(BigInt(0)).min(BigInt(Long.MaxValue)).toLong

  def ceilDivide(value: BigInt, divisor: BigInt): BigInt = {
    require(value >= 0 && divisor > 0, "Invalid fee division")
    (value + divisor - 1) / divisor
  }

  def fresh(samples: Vector[ConfirmedFeeSample], now: Long): Vector[ConfirmedFeeSample] = {
    samples.filter { sample =>
      val age = BigInt(now) - sample.confirmedAt
      age >= 0 && age <= HistoryWindowMillis && sample.fee > 0 && sample.size > 0
    }.takeRight(MaxSamples)
  }

  private def lowerRate(a: (Long, Int), b: (Long, Int)): Boolean =
    BigInt(a._1) * b._2 < BigInt(b._1) * a._2

  def quote(samples: Vector[ConfirmedFeeSample],
            queued: Seq[(Long, Int)],
            now: Long,
            minimumFee: Long,
            txSize: Int,
            waitMinutes: Int,
            blockIntervalMillis: Long,
            maxBlockSize: Int,
            maxTransactionSize: Int): FeeQuote = {
    require(minimumFee >= 0 && txSize > 0 && txSize <= maxTransactionSize, "Invalid fee quote size")
    require(waitMinutes >= 1 && waitMinutes <= MaxWaitMinutes, "Invalid fee quote wait time")
    require(blockIntervalMillis > 0 && maxBlockSize > 0, "Invalid fee quote block capacity")
    val recent = fresh(samples, now)
    val validQueue = queued.filter { case (fee, size) => fee >= 0 && size > 0 }
    val queueBytes = validQueue.foldLeft(BigInt(0)) { case (total, (_, size)) => total + size }
    val targetBlocks = (BigInt(waitMinutes) * 60000 / blockIntervalMillis).max(BigInt(1))
    // Leave room for reward transactions, proofs and block serialization overhead.
    val capacity = (BigInt(maxBlockSize) * 9 / 10).max(BigInt(1)) * targetBlocks
    val base = FeeQuote(minimumFee, minimumFee, txSize, waitMinutes, "minimum",
      recent.size, boundedLong(queueBytes), maxTransactionSize)
    if (queueBytes + txSize <= capacity) {
      base
    } else {
      val descending = validQueue.sortWith((a, b) => lowerRate(b, a))
      var prefix = BigInt(0)
      val cutoff = descending.find { case (_, size) =>
        prefix += size
        prefix + txSize > capacity
      }
      val queueFee = cutoff.map { case (fee, size) =>
        // One nano-unit above the clearing rate avoids ties without floating point.
        BigInt(fee) * txSize / size + 1
      }.getOrElse(BigInt(minimumFee))
      val historyFee = if (recent.size >= MinimumSamples) {
        val ascending = recent.map(sample => sample.fee -> sample.size).sortWith(lowerRate)
        val percentile = if (targetBlocks <= 1) 80 else 60
        val index = ((ascending.size - 1) * percentile + 99) / 100
        val (fee, size) = ascending(index)
        ceilDivide(BigInt(fee) * txSize, BigInt(size))
      } else BigInt(0)
      val recommendation = BigInt(minimumFee).max(queueFee).max(historyFee)
      val basis = if (historyFee > queueFee && historyFee > minimumFee) "recent-inclusions" else "mempool-pressure"
      base.copy(feeNano = boundedLong(recommendation), basis = basis)
    }
  }
}
