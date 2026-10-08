package org.ergoplatform.tools

import io.circe.syntax._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.ergoplatform.mining.{AutolykosSolution, CandidateBlock, CandidateGenerator, groupElemFromBytes, groupElemToBytes}
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.nodeView.state.{BoxHolder, ErgoState, UtxoState}
import org.ergoplatform.settings.{Algos, Args, ErgoSettings, ErgoSettingsReader, NetworkType}
import scorex.crypto.authds.ADValue
import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert}
import scorex.crypto.hash.Digest32
import scorex.util.encode.Base16

/** Derive the launch state and validate genesis transactions before exporting mining work. */
object ZyrexGenesis {
  private def candidate(settings: ErgoSettings, timestamp: Long, keyHex: String): CandidateBlock = {
    val pk = sigma.data.ProveDlog(groupElemFromBytes(Base16.decode(keyHex).get))
    val dir = Files.createTempDirectory("zyrex-genesis-").toFile
    val boxes = ErgoState.genesisBoxes(settings.chainSettings)
    val state = UtxoState.fromBoxHolder(BoxHolder(boxes), boxes.headOption, dir, settings, settings.launchParameters)
    try {
      val txs = CandidateGenerator.collectRewards(state.emissionBoxOpt, 0, Seq.empty, pk, state.stateContext)
      val (proof, root) = state.proofsForTransactions(txs).get
      val result = CandidateBlock(None, 4.toByte, settings.chainSettings.initialNBits, root,
        proof, txs, timestamp, ExtensionCandidate(Seq.empty), Array[Byte](0, 0, 0))
      val context = state.stateContext.upcoming(pk.value, timestamp, result.nBits, result.votes,
        org.ergoplatform.settings.ErgoValidationSettingsUpdate.empty, 4.toByte)
      txs.head.statefulValidity(boxes.toIndexedSeq, IndexedSeq.empty, context)(
        org.ergoplatform.wallet.interpreter.ErgoInterpreter(settings.launchParameters)).get
      result
    } finally {
      state.closeStorage()
      def remove(file: java.io.File): Unit = {
        if (file.isDirectory) Option(file.listFiles()).foreach(_.foreach(remove))
        require(file.delete(), s"Cannot remove temporary genesis file ${file.getName}")
      }
      remove(dir)
    }
  }

  private def write(block: ErgoFullBlock, output: String): Unit = {
    val path = Paths.get(output)
    require(!Files.exists(path), "Refusing to overwrite a genesis artifact")
    Files.createDirectories(path.toAbsolutePath.getParent)
    Files.write(path, block.asJson.spaces2.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW)
    println(s"ZYREX_GENESIS_ID=${block.id}")
  }

  def main(args: Array[String]): Unit = {
    val network = args.headOption.flatMap(NetworkType.fromString).getOrElse(NetworkType.DevNet)
    val config = args.lift(1).filterNot(_.startsWith("--"))
    val settings = ErgoSettingsReader.read(Args(config, Some(network)))
    require(settings.chainSettings.zyrex.nonEmpty, "Missing Zyrex chain settings")
    implicit val hash: Algos.HF = Algos.hash
    val prover = new BatchAVLProver[Digest32, Algos.HF](32, None)
    ErgoState.genesisBoxes(settings.chainSettings).foreach { box =>
      prover.performOneOperation(Insert(box.id, ADValue @@ box.bytes)).get
    }
    println(s"ZYREX_GENESIS_STATE=${Base16.encode(prover.digest)}")
    println(s"ZYREX_EMISSION_SCRIPT_BYTES=${settings.chainSettings.emissionProposition.bytes.length}")
    settings.chainSettings.zyrex.get.founderScripts.zipWithIndex.foreach { case (script, i) =>
      println(s"ZYREX_FOUNDER_${i + 1}=${settings.addressEncoder.fromProposition(script).get}")
    }
    val mineIndex = args.indexOf("--mine")
    val draftIndex = args.indexOf("--draft")
    val finalizeIndex = args.indexOf("--finalize")
    if (Seq(mineIndex, draftIndex, finalizeIndex).exists(_ >= 0)) {
      require(prover.digest.sameElements(settings.chainSettings.genesisStateDigest), "Configured genesis state is incorrect")
      require(network != NetworkType.MainNet, "Mainnet genesis requires a separate launch review")
    }
    if (finalizeIndex >= 0) {
      require(network == NetworkType.TestNet, "Finalize is restricted to the public testnet")
      val raw = new String(Files.readAllBytes(Paths.get(args(finalizeIndex + 1))), StandardCharsets.UTF_8)
      val draft = io.circe.parser.decode[ErgoFullBlock](raw).toTry.get
      val h = draft.header
      val rebuilt = candidate(settings, h.timestamp, Base16.encode(groupElemToBytes(h.powSolution.pk)))
      val zero = AutolykosSolution(h.powSolution.pk, AutolykosSolution.wForV2, Array.fill[Byte](8)(0), BigInt(0))
      require(CandidateGenerator.completeBlock(rebuilt, zero).asJson == draft.asJson,
        "Draft does not match the configured genesis transactions and state")
      val solution = zero.copy(n = Base16.decode(args(finalizeIndex + 2)).get)
      require(solution.n.length == 8, "Autolykos v2 requires an eight-byte nonce")
      val block = CandidateGenerator.completeBlock(rebuilt, solution)
      settings.chainSettings.powScheme.validate(block.header).get
      write(block, args(finalizeIndex + 3))
    } else if (mineIndex >= 0 || draftIndex >= 0) {
      val index = if (mineIndex >= 0) mineIndex else draftIndex
      val pk = sigma.data.ProveDlog(groupElemFromBytes(Base16.decode(args(index + 2)).get))
      val timestamp = if (network == NetworkType.DevNet) 1791417600000L else args(index + 3).toLong
      val work = candidate(settings, timestamp, args(index + 2))
      val solution = if (draftIndex >= 0) {
        AutolykosSolution(pk.value, AutolykosSolution.wForV2, Array.fill[Byte](8)(0), BigInt(0))
      } else {
        settings.chainSettings.powScheme.proveCandidate(work, BigInt(1), 0, 100000).get.header.powSolution.copy(pk = pk.value)
      }
      val block = CandidateGenerator.completeBlock(work, solution)
      if (draftIndex < 0) settings.chainSettings.powScheme.validate(block.header).get
      write(block, args(index + 1))
      if (draftIndex >= 0) {
        val external = settings.chainSettings.powScheme.deriveExternalCandidate(work, pk)
        Files.write(Paths.get(args(index + 1) + ".work.json"), external.asJson.spaces2.getBytes(StandardCharsets.UTF_8))
        println("Unmined draft only; a real Autolykos v2 solution is required")
      }
    }
  }
}
