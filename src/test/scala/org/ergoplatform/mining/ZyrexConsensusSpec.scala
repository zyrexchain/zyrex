package org.ergoplatform.mining

import java.io.File
import java.nio.file.Files
import org.ergoplatform.core.idToVersion
import com.typesafe.config.ConfigFactory
import org.ergoplatform.{ZyrexAddressEncoder, ErgoBox, ErgoBoxCandidate, Pay2SAddress, Pay2SHAddress}
import org.ergoplatform.mining.emission.ZyrexEmissionRules
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.ErgoContext
import org.ergoplatform.nodeView.state.{ErgoState, ErgoStateContext, UtxoState, VotingData}
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.history.popow.NipopowAlgos
import org.ergoplatform.sdk.wallet.protocol.context.TransactionContext
import org.ergoplatform.settings.{ErgoSettingsReader, ErgoValidationSettings, ErgoValidationSettingsUpdate}
import org.ergoplatform.utils.ErgoCorePropertyTest
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import org.ergoplatform.wallet.protocol.context.InputContext
import scorex.util.encode.Base16
import sigma.Colls
import sigma.ast.IntConstant
import scala.util.Failure
import scala.concurrent.duration._

class ZyrexConsensusSpec extends ErgoCorePropertyTest {
  import org.ergoplatform.utils.ErgoCoreTestConstants.defaultMinerSecret
  import org.ergoplatform.utils.generators.ErgoCoreGenerators.defaultHeaderGen
  import ZyrexEmissionRules._

  private val config = ConfigFactory.parseFile(new File("src/main/resources/devnet.conf"))
    .withFallback(ConfigFactory.parseFile(new File("src/main/resources/application.conf")))
    .withFallback(ConfigFactory.defaultReference()).resolve()
  private val settings = ErgoSettingsReader.fromConfig(config)
  private val chain = settings.chainSettings
  private val rules = chain.emissionRules.asInstanceOf[ZyrexEmissionRules]
  private val pk = defaultMinerSecret.publicImage
  private val header = defaultHeaderGen.sample.get

  private def context(height: Int): ErgoStateContext = new ErgoStateContext(
    Seq(header.copy(height = height, version = 4.toByte,
      powSolution = header.powSolution.copy(pk = pk.value))), None, chain.genesisStateDigest,
    settings.launchParameters, ErgoValidationSettings.initial, VotingData.empty)(chain)

  private def claim(height: Int): (ErgoBox, ErgoTransaction, ErgoStateContext) = {
    val box = new ErgoBox(rules.remainingCoinsAfterHeight(height - 1), chain.emissionProposition,
      Colls.emptyColl, Map(ErgoBox.R4 -> IntConstant(chain.zyrex.get.networkTag)),
      ErgoBox.allZerosModifierId, 0.toShort, height - 1)
    val ctx = context(height)
    val tx = CandidateGenerator.collectRewards(Some(box), height - 1, Seq.empty, pk, ctx).head
    (box, tx, ctx)
  }

  private def valid(tx: ErgoTransaction, box: ErgoBox, ctx: ErgoStateContext): Boolean =
    tx.statefulValidity(IndexedSeq(box), IndexedSeq.empty, ctx)(
      ErgoInterpreter(ctx.currentParameters)).isSuccess

  private def scriptValid(tx: ErgoTransaction, box: ErgoBox, ctx: ErgoStateContext): Boolean = {
    val proof = tx.inputs.head.spendingProof
    val execution = new ErgoContext(ctx, TransactionContext(IndexedSeq(box), IndexedSeq.empty, tx),
      InputContext(0.toShort, proof.extension), ctx.currentParameters.maxBlockCost.toLong, 0L)
    ErgoInterpreter(ctx.currentParameters).verify(box.ergoTree, execution, proof, tx.messageToSign)
      .toOption.exists(_._1)
  }

  private def resized(out: ErgoBoxCandidate, value: Long): ErgoBoxCandidate =
    new ErgoBoxCandidate(value, out.ergoTree, out.creationHeight, out.additionalTokens, out.additionalRegisters)

  property("exact integer emission, no premine and final supply") {
    rules.emissionAtHeight(0) shouldBe 0L
    rules.emissionAtHeight(1) shouldBe 100000000000L
    rules.minersRewardAtHeight(1) shouldBe 90000000000L
    rules.founderRewardAtHeight(1) shouldBe 5000000000L
    TotalSupply shouldBe 86399999993520000L
    rules.issuedCoinsAfterHeight(LastEmissionHeight) shouldBe TotalSupply
    rules.remainingCoinsAfterHeight(LastEmissionHeight) shouldBe 0L
    rules.issuedCoinsAfterHeight(Long.MaxValue) shouldBe TotalSupply
    rules.remainingFoundationRewardAtHeight(LastEmissionHeight) shouldBe 0L
    val boxes = ErgoState.genesisBoxes(chain)
    boxes.size shouldBe 1
    boxes.head.value shouldBe TotalSupply
    boxes.head.ergoTree shouldBe chain.emissionProposition
  }

  property("mining starts with zero or one known timestamp") {
    CandidateGenerator.getBlockMiningTimeAvg(IndexedSeq.empty) shouldBe 0.millis
    CandidateGenerator.getBlockMiningTimeAvg(IndexedSeq(1791417600000L)) shouldBe 0.millis
    CandidateGenerator.getBlockMiningTimeAvg(IndexedSeq(60000L, 120000L)) shouldBe 60.seconds
  }

  property("all halving boundaries and nano-unit tail execute under consensus and ErgoScript") {
    for (epoch <- Rates.indices) {
      val first = epoch * HalvingInterval + 1
      val last = (epoch + 1) * HalvingInterval
      rules.emissionAtHeight(first) shouldBe Rates(epoch)
      rules.emissionAtHeight(last) shouldBe Rates(epoch)
      for (height <- Seq(first, last)) {
        val (box, tx, ctx) = claim(height)
        valid(tx, box, ctx) shouldBe true
        scriptValid(tx, box, ctx) shouldBe true
        tx.outputCandidates.map(_.value).sum shouldBe box.value
        val founder = Rates(epoch) / 20
        if (founder > 0) tx.outputCandidates.takeRight(2).map(_.value) shouldBe IndexedSeq(founder, founder)
      }
    }
    rules.emissionAtHeight(LastEmissionHeight + 1) shouldBe 0L
  }

  property("both validation layers reject omitted, redirected or underpaid founder payouts") {
    val (box, tx, ctx) = claim(1)
    val outs = tx.outputCandidates
    val diverted = new ErgoBoxCandidate(outs(2).value, outs(1).ergoTree, 1)
    val mutations = Seq(
      outs.take(2).updated(1, resized(outs(1), outs(1).value + 10000000000L)),
      outs.updated(2, diverted),
      outs.updated(2, resized(outs(2), outs(2).value - 1)).updated(1, resized(outs(1), outs(1).value + 1)),
      outs.updated(0, resized(outs(0), outs(0).value - 1)).updated(1, resized(outs(1), outs(1).value + 1)),
      outs.updated(1, new ErgoBoxCandidate(outs(1).value, outs(2).ergoTree, 1)),
      IndexedSeq(outs(0), outs(1), outs(3), outs(2))
    )
    mutations.foreach { outputs =>
      val bad = ErgoTransaction(tx.inputs, tx.dataInputs, outputs)
      valid(bad, box, ctx) shouldBe false
      scriptValid(bad, box, ctx) shouldBe false
    }
  }

  property("emission is mandatory even in a block with no normal transactions") {
    val ctx = context(1)
    ErgoState.execTransactions(Seq.empty, ctx, settings.nodeSettings)(
      _ => Failure(new IllegalArgumentException("missing input"))).isValid shouldBe false
    val (box, tx, _) = claim(1)
    ErgoState.execTransactions(Seq(tx), ctx, settings.nodeSettings)(
      _ => scala.util.Success(box)).isValid shouldBe true
    ErgoState.execTransactions(Seq(tx, tx), ctx, settings.nodeSettings)(
      _ => scala.util.Success(box)).isValid shouldBe false
  }

  property("network address prefixes round trip and reject foreign networks") {
    for (prefix <- Seq[Byte](48, 64, 80)) {
      val encoder = ZyrexAddressEncoder(prefix)
      val addresses = Seq(encoder.fromProposition(chain.zyrex.get.founderScripts.head).get,
        Pay2SAddress(chain.emissionProposition)(encoder),
        new Pay2SHAddress(Array.fill[Byte](24)(1))(encoder))
      for (addr <- addresses) {
        if (prefix != 80.toByte) addr.toString.startsWith("ZRX") shouldBe true
        encoder.fromString(addr.toString).get.toString shouldBe addr.toString
        for (foreign <- Seq[Byte](0, 16, 48, 64, 80).filterNot(_ == prefix)) {
          ZyrexAddressEncoder(foreign).fromString(addr.toString).isFailure shouldBe true
        }
        val brand = if (prefix == 80.toByte) "" else "ZRX"
        val raw = scorex.util.encode.Base58.decode(addr.toString.stripPrefix(brand)).get
        raw(raw.length - 1) = (raw.last ^ 1).toByte
        encoder.fromString(brand + scorex.util.encode.Base58.encode(raw)).isFailure shouldBe true
        if (brand.nonEmpty) encoder.fromString(addr.toString.stripPrefix(brand)).isFailure shouldBe true
      }
    }
  }

  property("founder keys reject duplicate encodings and the point at infinity") {
    val params = chain.zyrex.get
    intercept[IllegalArgumentException] {
      params.copy(founderPubkeys = Seq(params.founderPubkeys.head, params.founderPubkeys.head.toUpperCase))
    }
    intercept[IllegalArgumentException] {
      params.copy(founderPubkeys = Seq("00" * 33, params.founderPubkeys(1)))
    }
  }

  property("recipients and network identity are committed in the genesis script") {
    val params = chain.zyrex.get
    params.copy(founderPubkeys = params.founderPubkeys.reverse).emissionProposition(3) should not be chain.emissionProposition
    params.copy(networkTag = params.networkTag + 1).emissionProposition(3) should not be chain.emissionProposition
    chain.genesisStateDigestHex should not be "cb63aa99a3060f341781d8662b58bf18b9ad258db4fe88d09f8f71cb668cad4502"
    Base16.encode(settings.scorexSettings.network.magicBytes) shouldBe "5a595244"
    settings.launchParameters.blockVersion shouldBe 4.toByte
  }

  property("rollback and the first voting epoch preserve the alternate emission chain") {
    val source = scala.io.Source.fromFile("src/main/resources/genesis/devnet.json")
    val genesis = try io.circe.parser.decode[ErgoFullBlock](source.mkString).toTry.get
      finally source.close()
    val dir = Files.createTempDirectory("zyrex-rollback-").toFile
    val peerDir = Files.createTempDirectory("zyrex-peer-").toFile
    var state = ErgoState.generateGenesisUtxoState(dir, settings)._1
    var peer = ErgoState.generateGenesisUtxoState(peerDir, settings)._1
    def applyBlock(s: UtxoState, block: ErgoFullBlock): UtxoState =
      s.applyModifier(block, None)(_ => ()).get
    def next(s: UtxoState, parent: ErgoFullBlock, delta: Long): ErgoFullBlock = {
      val txs = CandidateGenerator.collectRewards(s.emissionBoxOpt, parent.header.height,
        Seq.empty, pk, s.stateContext)
      val (proof, digest) = s.proofsForTransactions(txs).get
      val popow = new NipopowAlgos(chain)
      val interlinks = popow.interlinksToExtension(
        popow.updateInterlinks(Some(parent.header), Some(parent.extension)))
      val nextHeight = parent.header.height + 1
      val extension = if (nextHeight % chain.voting.votingLength == 0) {
        val (parameters, update) = s.stateContext.currentParameters.update(nextHeight, false,
          s.stateContext.votingData.epochVotes, ErgoValidationSettingsUpdate.empty, chain.voting)
        parameters.toExtensionCandidate ++ interlinks ++
          s.stateContext.validationSettings.updated(update).toExtensionCandidate
      } else interlinks
      val candidate = CandidateBlock(Some(parent.header), 4.toByte, chain.initialNBits,
        digest, proof, txs, parent.header.timestamp + delta,
        extension, Array[Byte](0, 0, 0))
      chain.powScheme.proveCandidate(candidate, defaultMinerSecret.w, 0L, 100000L).get
    }
    def remove(file: File): Unit = {
      if (file.isDirectory) Option(file.listFiles()).foreach(_.foreach(remove))
      require(file.delete(), "Cannot remove temporary reorg test file")
    }
    try {
      state = applyBlock(state, genesis)
      peer = applyBlock(peer, genesis)
      val old2 = next(state, genesis, 60000L)
      state = applyBlock(state, old2)
      val old3 = next(state, old2, 60000L)
      state = applyBlock(state, old3)
      state = state.rollbackTo(idToVersion(genesis.id)).get
      state.rootDigest shouldBe genesis.header.stateRoot
      state.emissionBoxOpt.get.id shouldBe genesis.transactions.head.outputs.head.id
      old3.transactions.head.outputs.foreach { out => state.boxById(out.id) shouldBe None }
      var parent = genesis
      for (_ <- 2 to 4) {
        val block = next(state, parent, 61000L)
        state = applyBlock(state, block)
        peer = applyBlock(peer, block)
        parent = block
      }
      state.rootDigest shouldBe peer.rootDigest
      state.emissionBoxOpt.get.value shouldBe rules.remainingCoinsAfterHeight(4)
      state.stateContext.currentHeight shouldBe 4
      for (_ <- 5 to 129) {
        val block = next(state, parent, 60000L)
        state = applyBlock(state, block)
        peer = applyBlock(peer, block)
        parent = block
      }
      state.rootDigest shouldBe peer.rootDigest
      state.emissionBoxOpt.get.value shouldBe rules.remainingCoinsAfterHeight(129)
      state.stateContext.currentParameters.height shouldBe 128
      state.stateContext.currentParameters.blockVersion shouldBe 4.toByte
    } finally {
      state.closeStorage()
      peer.closeStorage()
      remove(dir)
      remove(peerDir)
    }
  }
}
