package org.ergoplatform.mining

import akka.actor.ActorSystem
import akka.pattern.StatusReply
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.ergoplatform.mining.CandidateGenerator.{Candidate, GenerateCandidate, ReadCandidateDetails, SubmitSolution}
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages.SemanticallyFailedModification
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.nodeView.{ErgoNodeViewRef, ErgoReadersHolderRef, LocallyGeneratedModifier}
import org.ergoplatform.settings.ErgoSettingsReader
import org.ergoplatform.utils.ErgoCoreTestConstants.defaultMinerSecret
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.util.encode.Base16
import sigma.crypto.CryptoConstants
import sigma.data.ProveDlog

import java.nio.file.Files
import scala.concurrent.Await
import scala.concurrent.duration._

class ZyrexMiningActorSpec extends AnyFlatSpec with Matchers {
  it should "return atomic current and previous work and clear solved metadata after invalidation" in {
    val directory = Files.createTempDirectory("zyrex-mining-snapshot-")
    val config = ConfigFactory.parseString(s"""
      zyrex.directory = "${directory.toString}"
      zyrex.node.mining = true
      zyrex.node.offlineGeneration = true
      zyrex.node.useExternalMiner = true
      zyrex.node.stateType = "utxo"
      zyrex.wallet.secretStorage.secretDir = "${directory.resolve("wallet").toString}"
      """).withFallback(ConfigFactory.load())
    val settings = ErgoSettingsReader.fromConfig(config.resolve())
    implicit val system: ActorSystem = ActorSystem("zyrex-mining-snapshot")
    try {
      val replies = new TestProbe(system)
      val view = new TestProbe(system)
      val realView = ErgoNodeViewRef(settings)
      val readers = ErgoReadersHolderRef(realView)
      val generator = CandidateGenerator(defaultMinerSecret.publicImage, readers, view.ref, settings)(system)
      def snapshot(): MiningWorkSnapshot = {
        generator.tell(ReadCandidateDetails, replies.ref)
        replies.expectMsgPF(15.seconds) { case StatusReply.Success(value: MiningWorkSnapshot) => value }
      }
      def generate(pk: Option[ProveDlog] = None): Candidate = {
        Thread.sleep(2)
        generator.tell(GenerateCandidate(Seq.empty, reply = true, forced = true, pk), replies.ref)
        replies.expectMsgPF(15.seconds) { case StatusReply.Success(value: Candidate) => value }
      }
      def message(candidate: Candidate): String = Base16.encode(candidate.externalVersion.msg)

      val first = snapshot()
      first.nodeSolved shouldBe false
      first.activeMessages.head shouldBe message(first.candidate)
      first.parentId shouldBe AutolykosPowScheme.derivedHeaderFields(first.candidate.candidateBlock.parentOpt)._1
      val second = generate()
      snapshot().activeMessages shouldBe Seq(message(second), message(first.candidate))
      val third = generate()
      val thirdSnapshot = snapshot()
      thirdSnapshot.activeMessages shouldBe Seq(message(third), message(second))
      thirdSnapshot.activeMessages should not contain message(first.candidate)

      val alternate = generate(Some(ProveDlog(CryptoConstants.dlogGroup.generator)))
      val restored = snapshot()
      restored.candidate.externalVersion.pk shouldBe defaultMinerSecret.publicImage
      restored.activeMessages shouldBe Seq(message(restored.candidate))
      restored.activeMessages should not contain message(alternate)

      val solved = settings.chainSettings.powScheme.proveCandidate(
        restored.candidate.candidateBlock, defaultMinerSecret.w, 0, 1000).get
      generator.tell(SubmitSolution(solved.header.powSolution, Some(restored.candidate.externalVersion.msg)), replies.ref)
      replies.expectMsg(3.seconds, StatusReply.Success(()))
      val waiting = snapshot()
      waiting.nodeSolved shouldBe true
      waiting.activeMessages.head shouldBe message(restored.candidate)
      generator.tell(SemanticallyFailedModification(solved.header.modifierTypeId,
        solved.header.id, new IllegalArgumentException("Owned regression invalidation")), replies.ref)
      val recovered = snapshot()
      recovered.nodeSolved shouldBe false
      recovered.activeMessages shouldBe Seq(message(recovered.candidate))
    } finally {
      Await.result(system.terminate(), 15.seconds)
    }
  }

  it should "preserve cached work after stale requests and send the requested previous native block" in {
    val directory = Files.createTempDirectory("zyrex-mining-binding-")
    val config = ConfigFactory.parseString(s"""
      zyrex.directory = "${directory.toString}"
      zyrex.node.mining = true
      zyrex.node.offlineGeneration = true
      zyrex.node.useExternalMiner = true
      zyrex.node.stateType = "utxo"
      zyrex.wallet.secretStorage.secretDir = "${directory.resolve("wallet").toString}"
      """).withFallback(ConfigFactory.load())
    val settings = ErgoSettingsReader.fromConfig(config.resolve())
    implicit val system: ActorSystem = ActorSystem("zyrex-mining-binding")
    try {
      val replies = new TestProbe(system)
      val view = new TestProbe(system)
      val realView = ErgoNodeViewRef(settings)
      val readers = ErgoReadersHolderRef(realView)
      val generator = CandidateGenerator(defaultMinerSecret.publicImage, readers, view.ref, settings)(system)
      def generate(command: GenerateCandidate): Candidate = {
        generator.tell(command, replies.ref)
        replies.expectMsgPF(15.seconds) { case StatusReply.Success(candidate: Candidate) => candidate }
      }
      val previous = generate(GenerateCandidate(Seq.empty, reply = true, forced = true))
      val alternatePk = ProveDlog(CryptoConstants.dlogGroup.generator)
      val current = generate(GenerateCandidate(Seq.empty, reply = true, forced = true, Some(alternatePk)))
      current.externalVersion.msg.sameElements(previous.externalVersion.msg) shouldBe false
      val pow = settings.chainSettings.powScheme
      val solved = pow.proveCandidate(previous.candidateBlock, defaultMinerSecret.w, 0, 1000).get
      val solution = solved.header.powSolution
      pow.validate(CandidateGenerator.completeBlock(current.candidateBlock, solution).header).isSuccess shouldBe true

      generator.tell(SubmitSolution(solution, Some(Array.fill(32)(99: Byte))), replies.ref)
      replies.expectMsgPF(3.seconds) {
        case response: StatusReply[_] if response.isError =>
          response.getError shouldBe a[MiningSolutionSelection.Rejected]
      }
      view.expectNoMessage(100.millis)
      val cached = generate(GenerateCandidate(Seq.empty, reply = true, forced = false, Some(alternatePk)))
      cached.externalVersion.msg.toSeq shouldBe current.externalVersion.msg.toSeq

      generator.tell(SubmitSolution(solution, Some(previous.externalVersion.msg)), replies.ref)
      replies.expectMsg(3.seconds, StatusReply.Success(()))
      val header = view.expectMsgPF(3.seconds) {
        case LocallyGeneratedModifier(value: Header) => value
      }
      Base16.encode(pow.msgByHeader(header)) shouldBe Base16.encode(previous.externalVersion.msg)
      header.id shouldBe solved.id
      solved.mandatoryBlockSections.foreach(section => view.expectMsg(LocallyGeneratedModifier(section)))
    } finally {
      Await.result(system.terminate(), 15.seconds)
    }
  }
}
