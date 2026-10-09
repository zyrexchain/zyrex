package org.ergoplatform.mining

import io.circe.Json
import io.circe.syntax._
import org.ergoplatform.http.api.requests.MiningSolutionRequest
import org.ergoplatform.mining.CandidateGenerator.Candidate
import org.ergoplatform.mining.difficulty.DifficultySerializer
import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCoreTestConstants.emptyProverResult
import org.ergoplatform.{ErgoBoxCandidate, Input}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.crypto.authds.{ADDigest, ADKey, SerializedAdProof}
import scorex.util.encode.Base16
import sigma.crypto.CryptoConstants
import sigma.data.ProveDlog

class ZyrexMiningBindingSpec extends AnyFlatSpec with Matchers {
  private val pow = new AutolykosPowScheme(32, 26)
  private val pk = CryptoConstants.dlogGroup.generator
  private val solution = AutolykosSolution(pk, AutolykosSolution.wForV2, Array.fill(8)(0: Byte), 0)
  private val transaction = ErgoTransaction(
    IndexedSeq(Input(ADKey @@ Array.fill(32)(0: Byte), emptyProverResult)), IndexedSeq.empty,
    IndexedSeq(new ErgoBoxCandidate(1000000000L, Constants.TrueTree, 0))
  )

  private def candidate(timestamp: Long, difficulty: BigInt = BigInt(1)): Candidate = {
    val block = CandidateBlock(
      None, 2: Byte, DifficultySerializer.encodeCompactBits(difficulty),
      ADDigest @@ Array.fill(33)(0: Byte), SerializedAdProof @@ Array.emptyByteArray,
      Seq(transaction), timestamp, ExtensionCandidate(Seq.empty), Array.fill(3)(0: Byte)
    )
    Candidate(block, pow.deriveExternalCandidate(block, ProveDlog(pk), Seq.empty), Seq.empty)
  }

  private val previous = candidate(1000)
  private val current = candidate(2000)

  it should "select exactly the previous message when the same nonce validates both candidates" in {
    current.externalVersion.msg.sameElements(previous.externalVersion.msg) shouldBe false
    pow.validate(CandidateGenerator.completeBlock(current.candidateBlock, solution).header).isSuccess shouldBe true
    pow.validate(CandidateGenerator.completeBlock(previous.candidateBlock, solution).header).isSuccess shouldBe true
    val block = MiningSolutionSelection.complete(
      Some(current), Some(previous), solution, Some(previous.externalVersion.msg), pow
    ).get
    block.header.timestamp shouldBe previous.candidateBlock.timestamp
    pow.msgByHeader(block.header).toSeq shouldBe previous.externalVersion.msg.toSeq
  }

  it should "select exactly the current message" in {
    val block = MiningSolutionSelection.complete(
      Some(current), Some(previous), solution, Some(current.externalVersion.msg), pow
    ).get
    pow.msgByHeader(block.header).toSeq shouldBe current.externalVersion.msg.toSeq
  }

  it should "reject unknown work without falling back to a valid current nonce" in {
    val rejected = MiningSolutionSelection.complete(
      Some(current), Some(previous), solution, Some(Array.fill(32)(99: Byte)), pow
    )
    rejected.failed.get shouldBe a[MiningSolutionSelection.Rejected]
    MiningSolutionSelection.complete(
      Some(current), Some(previous), solution, Some(current.externalVersion.msg), pow
    ).isSuccess shouldBe true
  }

  it should "reject malformed work even for a valid nonce" in {
    Seq(0, 31, 33).foreach { length =>
      MiningSolutionSelection.complete(
        Some(current), Some(previous), solution, Some(Array.fill(length)(0: Byte)), pow
      ).failed.get shouldBe a[MiningSolutionSelection.Rejected]
    }
  }

  it should "reject invalid PoW for bound work without trying another valid candidate" in {
    val difficult = candidate(3000, BigInt(1) << 240)
    pow.validate(CandidateGenerator.completeBlock(difficult.candidateBlock, solution).header).isFailure shouldBe true
    MiningSolutionSelection.complete(
      Some(current), Some(difficult), solution, Some(difficult.externalVersion.msg), pow
    ).failed.get shouldBe a[MiningSolutionSelection.Rejected]
  }

  it should "keep legacy preference for a valid current candidate when msg is absent" in {
    val block = MiningSolutionSelection.complete(Some(current), Some(previous), solution, None, pow).get
    block.header.timestamp shouldBe current.candidateBlock.timestamp
  }

  it should "keep legacy fallback when only the previous candidate validates" in {
    val difficult = candidate(3000, BigInt(1) << 240)
    val block = MiningSolutionSelection.complete(Some(difficult), Some(previous), solution, None, pow).get
    block.header.timestamp shouldBe previous.candidateBlock.timestamp
  }

  it should "decode legacy solutions and exact hexadecimal messages without changing PoW fields" in {
    val legacy = solution.asJson.as[MiningSolutionRequest].toOption.get
    legacy.msg shouldBe None
    legacy.solution.n.toSeq shouldBe solution.n.toSeq
    val json = solution.asJson.deepMerge(Json.obj("msg" -> Json.fromString(Base16.encode(previous.externalVersion.msg))))
    val bound = json.as[MiningSolutionRequest].toOption.get
    bound.msg.get.toSeq shouldBe previous.externalVersion.msg.toSeq
    bound.solution.n.toSeq shouldBe solution.n.toSeq
    bound.solution.pk shouldBe solution.pk
  }

  it should "reject null, non-string, short, long and non-hexadecimal msg fields at decoding" in {
    val malformed = Seq(Json.Null, Json.fromInt(1), Json.fromString(""),
      Json.fromString("00" * 31), Json.fromString("00" * 33), Json.fromString("gg" * 32))
    malformed.foreach { msg =>
      solution.asJson.deepMerge(Json.obj("msg" -> msg)).as[MiningSolutionRequest].isLeft shouldBe true
    }
  }
}
