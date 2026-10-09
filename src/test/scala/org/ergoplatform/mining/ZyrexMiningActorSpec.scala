package org.ergoplatform.mining

import akka.actor.ActorSystem
import akka.pattern.StatusReply
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.ergoplatform.mining.CandidateGenerator.{Candidate, GenerateCandidate, SubmitSolution}
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
