package org.ergoplatform.mining

import io.circe.syntax._
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.Input
import org.ergoplatform.mining.CandidateGenerator.Candidate
import org.ergoplatform.mining.difficulty.DifficultySerializer
import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCoreTestConstants.emptyProverResult
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.crypto.authds.{ADDigest, ADKey, SerializedAdProof}
import scorex.util.encode.Base16
import sigma.crypto.CryptoConstants
import sigma.data.ProveDlog

class ZyrexMiningWorkSnapshotSpec extends AnyFlatSpec with Matchers {
  private val pow = new AutolykosPowScheme(32, 26)
  private val pk = ProveDlog(CryptoConstants.dlogGroup.generator)
  private val transaction = ErgoTransaction(
    IndexedSeq(Input(ADKey @@ Array.fill(32)(0: Byte), emptyProverResult)), IndexedSeq.empty,
    IndexedSeq(new ErgoBoxCandidate(1000000000L, Constants.TrueTree, 0))
  )
  private def candidate(timestamp: Long): Candidate = {
    val block = CandidateBlock(
      None, 2: Byte, DifficultySerializer.encodeCompactBits(BigInt(1)),
      ADDigest @@ Array.fill(33)(0: Byte), SerializedAdProof @@ Array.emptyByteArray,
      Seq(transaction), timestamp, ExtensionCandidate(Seq.empty), Array.fill(3)(0: Byte)
    )
    Candidate(block, pow.deriveExternalCandidate(block, pk, Seq.empty), Seq.empty)
  }
  private val previous = candidate(1000)
  private val current = candidate(2000)
  private def message(value: Candidate): String = Base16.encode(value.externalVersion.msg)

  it should "advertise only the current and immediately previous compatible native work" in {
    val snapshot = MiningWorkSnapshot(current, Some(previous), nodeSolved = false)
    snapshot.activeMessages shouldBe Seq(message(current), message(previous))
    snapshot.parentId shouldBe ("00" * 32)
    MiningWorkSnapshot(current, Some(current), nodeSolved = false).activeMessages shouldBe Seq(message(current))
    MiningWorkSnapshot(current, None, nodeSolved = false).activeMessages shouldBe Seq(message(current))
  }

  it should "exclude a previous candidate with another parent height public key or target" in {
    val otherPk = ProveDlog(CryptoConstants.dlogGroup.exponentiate(pk.value, BigInt(2).bigInteger))
    val parent = CandidateGenerator.completeBlock(previous.candidateBlock,
      AutolykosSolution(pk.value, AutolykosSolution.wForV2, Array.fill(8)(0: Byte), 0)).header
    val incompatible = Seq(
      previous.copy(candidateBlock = previous.candidateBlock.copy(parentOpt = Some(parent))),
      previous.copy(externalVersion = previous.externalVersion.copy(h = current.externalVersion.h.map(_ + 1))),
      previous.copy(externalVersion = previous.externalVersion.copy(pk = otherPk)),
      previous.copy(externalVersion = previous.externalVersion.copy(b = current.externalVersion.b + 1))
    )
    incompatible.foreach { old =>
      MiningWorkSnapshot(current, Some(old), nodeSolved = false).activeMessages shouldBe Seq(message(current))
    }
  }

  it should "extend the original work fields without modifying their values" in {
    val withProof = current.copy(externalVersion = pow.deriveExternalCandidate(
      current.candidateBlock, pk, Seq(transaction.id)))
    val original = withProof.externalVersion.asJson
    original.hcursor.downField("proof").focus.isDefined shouldBe true
    val snapshot = MiningWorkSnapshot(withProof, Some(previous), nodeSolved = true).asJson
    val extra = Set("workVersion", "parentId", "activeMessages", "nodeSolved")
    snapshot.asObject.get.keys.toSet shouldBe original.asObject.get.keys.toSet ++ extra
    original.asObject.get.toIterable.foreach { case (key, value) =>
      snapshot.hcursor.downField(key).focus shouldBe Some(value)
    }
    snapshot.hcursor.downField("nodeSolved").as[Boolean] shouldBe Right(true)
    snapshot.hcursor.downField("workVersion").as[Int] shouldBe Right(1)
    withProof.externalVersion.asJson.noSpaces shouldBe original.noSpaces
  }
}
