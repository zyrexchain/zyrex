package org.ergoplatform.nodeView.mempool

import org.ergoplatform.{ErgoBoxCandidate, Input}
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnconfirmedTransaction}
import org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.SortingOption
import org.ergoplatform.utils.ErgoTestHelpers
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.crypto.authds.ADKey
import scorex.util.bytesToId

class ZyrexFeeEstimationSpec extends AnyFlatSpec with ErgoTestHelpers with Matchers {
  import org.ergoplatform.utils.ErgoCoreTestConstants._
  import org.ergoplatform.utils.ErgoNodeTestConstants._

  private val now = 2000000000L
  private val floor = 1000000L
  private def sample(seed: Byte, fee: Long = 10000000L, size: Int = 100): ConfirmedFeeSample =
    ConfirmedFeeSample(bytesToId(Array.fill(32)(seed)), now, fee, size)

  private def quote(samples: Vector[ConfirmedFeeSample] = Vector.empty,
                    queue: Seq[(Long, Int)] = Seq.empty,
                    size: Int = 100,
                    wait: Int = 1,
                    time: Long = now,
                    minimum: Long = floor): FeeQuote =
    FeeEstimation.quote(samples, queue, time, minimum, size, wait, 60000L, 1000, 98304)

  private def tx(seed: Byte, fee: Long, input: Byte = 0): ErgoTransaction = {
    val inputSeed = if (input == 0) seed else input
    ErgoTransaction(IndexedSeq(new Input(ADKey @@ Array.fill(32)(inputSeed), emptyProverResult)),
      IndexedSeq(new ErgoBoxCandidate(fee, feeProp, creationHeight = seed.toInt)))
  }

  it should "keep the floor for an empty or uncongested queue even after expensive blocks" in {
    val samples = Vector(sample(1), sample(2), sample(3))
    quote(samples).feeNano shouldBe floor
    quote(samples, Seq(90000000L -> 100)).feeNano shouldBe floor
    quote(samples).basis shouldBe "minimum"
  }

  it should "use the byte queue clearing rate with exact upward rounding" in {
    val result = quote(queue = Seq(9999991L -> 300, 9000001L -> 300, 8000000L -> 300), size = 301)
    result.feeNano shouldBe (BigInt(9000001L) * 301 / 300 + 1).toLong
    result.basis shouldBe "mempool-pressure"
    result.mempoolBytes shouldBe 900L
  }

  it should "use sufficient fresh included history when it exceeds the congested clearing rate" in {
    val samples = Vector(sample(1, 3000001L, 333), sample(2, 4000001L, 333), sample(3, 5000001L, 333))
    val result = quote(samples, Seq(1000000L -> 900), size = 101)
    result.feeNano shouldBe ((BigInt(5000001L) * 101 + 332) / 333).toLong
    result.basis shouldBe "recent-inclusions"
    result.sampleCount shouldBe 3
    quote(samples.take(2), Seq(1000000L -> 900), size = 101).feeNano shouldBe floor
  }

  it should "expire old observations and reject future observations" in {
    val samples = Vector(sample(1), sample(2), sample(3), sample(4).copy(confirmedAt = now + 1))
    quote(samples).sampleCount shouldBe 3
    quote(samples, time = now + FeeEstimation.HistoryWindowMillis + 2).sampleCount shouldBe 0
    val expired = MemPoolStatistics(now, snapTime = now)
      .addConfirmed(sample(1).copy(confirmedAt = 0L), now)
    expired.confirmedFees shouldBe empty
  }

  it should "bound history and replace duplicate active-chain observations" in {
    val samples = (1 to 600).foldLeft(MemPoolStatistics(now, snapTime = now)) { (stats, index) =>
      val id = bytesToId(java.nio.ByteBuffer.allocate(32).putInt(index).array())
      stats.addConfirmed(ConfirmedFeeSample(id, now, floor, 100), now)
    }
    samples.confirmedFees.size shouldBe FeeEstimation.MaxSamples
    val repeated = samples.addConfirmed(samples.confirmedFees.last.copy(fee = floor * 2), now)
    repeated.confirmedFees.size shouldBe FeeEstimation.MaxSamples
    repeated.confirmedFees.last.fee shouldBe floor * 2
  }

  it should "avoid overflow and preserve the configured minimum with large integer values" in {
    val samples = Vector(sample(1, Long.MaxValue, 1), sample(2, Long.MaxValue, 1), sample(3, Long.MaxValue, 1))
    quote(samples, Seq(Long.MaxValue -> 1000), size = 98304).feeNano shouldBe Long.MaxValue
    quote(minimum = Long.MaxValue).feeNano shouldBe Long.MaxValue
    FeeEstimation.ceilDivide(BigInt(Long.MaxValue) * 98304, BigInt(3)) should be > BigInt(Long.MaxValue)
  }

  it should "reject invalid sizes and horizons and use the requested time budget" in {
    Seq(0, -1, 98305).foreach { size => intercept[IllegalArgumentException](quote(size = size)) }
    Seq(0, -1, 61).foreach { wait => intercept[IllegalArgumentException](quote(wait = wait)) }
    val queue = Seq(10000000L -> 900)
    quote(queue = queue, wait = 1).feeNano should be > floor
    quote(queue = queue, wait = 2).feeNano shouldBe floor
  }

  it should "keep fee quotes independent of fee-per-cycle sorting and validation cost" in {
    val transactions = (1 to 8).map(seed => UnconfirmedTransaction(tx(seed.toByte, floor * seed), None))
    val byteSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(mempoolSorting = SortingOption.FeePerByte))
    val costSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(mempoolSorting = SortingOption.FeePerCycle))
    val bytes = ErgoMemPool.empty(byteSettings).put(transactions.map(_.withCost(10)))
    val cycles = ErgoMemPool.empty(costSettings)
      .put(transactions.map(_.withCost(20000000)))
    bytes.getFeeQuote(1, 100, 100).feeNano shouldBe cycles.getFeeQuote(1, 100, 100).feeNano
  }

  it should "record only included transactions and never invalidated or conflicting losers" in {
    val included = tx(1, floor)
    val loser = tx(2, floor * 10, input = 1)
    val invalid = UnconfirmedTransaction(tx(3, floor * 10), None)
    val pool = ErgoMemPool.empty(settings).put(Seq(UnconfirmedTransaction(loser, None), invalid))
      .invalidate(invalid)
    pool.stats.confirmedFees shouldBe empty
    val confirmed = pool.removeWithDoubleSpends(Seq(included))
    confirmed.size shouldBe 0
    confirmed.stats.confirmedFees.map(_.id) shouldBe Vector(included.id)
    confirmed.stats.confirmedFees.head.fee shouldBe floor
  }

  it should "discard rolled-back fee observations and avoid treating historical sync as recent" in {
    val transaction = tx(1, floor)
    val pool = ErgoMemPool.empty(settings).removeWithDoubleSpends(Seq(transaction))
    pool.stats.confirmedFees.map(_.id) shouldBe Vector(transaction.id)
    pool.withoutConfirmations(Set(transaction.id)).stats.confirmedFees shouldBe empty
    ErgoMemPool.empty(settings).removeWithDoubleSpends(Seq(transaction), confirmedAt = 0L)
      .stats.confirmedFees shouldBe empty
  }
}
