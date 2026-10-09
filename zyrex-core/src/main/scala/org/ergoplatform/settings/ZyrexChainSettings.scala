package org.ergoplatform.settings

import org.ergoplatform.{ErgoAddressEncoder, ErgoTreePredef, P2PKAddress}
import org.ergoplatform.mining.{groupElemFromBytes}
import org.ergoplatform.mining.emission.ZyrexEmissionRules
import scorex.util.encode.Base16
import sigma.Colls
import sigma.ast.{ErgoTree, SSigmaProp}
import sigma.compiler.SigmaCompiler
import sigma.compiler.ir.CompiletimeIRContext
import sigma.crypto.CryptoConstants
import sigma.data.ProveDlog

/** The two recipients and network identity are committed in the genesis emission script. */
case class ZyrexChainSettings(networkTag: Int, founderPubkeys: Seq[String]) {
  require(founderPubkeys.size == 2, "Zyrex requires exactly two founder public keys")
  require(founderPubkeys.map(_.toLowerCase(java.util.Locale.ROOT)).distinct.size == 2,
    "Founder public keys must be distinct")
  val founderScripts: IndexedSeq[ErgoTree] = founderPubkeys.map { key =>
    val bytes = Base16.decode(key).get
    require(bytes.length == 33 && (bytes.head == 2 || bytes.head == 3),
      "Founder public key must be compressed secp256k1 (33 bytes, prefix 02 or 03)")
    P2PKAddress(ProveDlog(groupElemFromBytes(bytes)))(ErgoAddressEncoder(48.toByte)).script
  }.toIndexedSeq

  def emissionProposition(delay: Int): ErgoTree = {
    val genericMiner = ErgoTreePredef.rewardOutputScript(delay,
      ProveDlog(CryptoConstants.dlogGroup.generator))
    val env: Map[String, Any] = Map(
      "rates" -> Colls.fromArray(ZyrexEmissionRules.Rates.toArray),
      "founder1" -> Colls.fromArray(founderScripts(0).bytes),
      "founder2" -> Colls.fromArray(founderScripts(1).bytes),
      "minerTemplate" -> Colls.fromArray(genericMiner.bytes),
      "networkTag" -> networkTag
    )
    val source = """{
      val epoch = (HEIGHT - 1) / 432000
      val reward = if (HEIGHT > 0 && epoch < rates.size) rates(epoch) else 0L
      val founder = reward / 20L
      val remaining = SELF.value - reward
      val offset = if (remaining > 0L) 1 else 0
      val foundersCount = if (founder > 0L) 2 else 0
      val miner = OUTPUTS(offset)
      val expectedMiner = substConstants(minerTemplate, Coll(1), Coll(proveDlog(CONTEXT.preHeader.minerPk)))
      val continuation = if (remaining > 0L) {
        val out = OUTPUTS(0)
        out.value == remaining && out.propositionBytes == SELF.propositionBytes &&
        out.creationInfo._1 == HEIGHT && out.tokens.size == 0 &&
        out.R4[Int].get == networkTag
      } else true
      val founders = if (founder > 0L) {
        val first = OUTPUTS(offset + 1)
        val second = OUTPUTS(offset + 2)
        first.value == founder && second.value == founder &&
        first.propositionBytes == founder1 && second.propositionBytes == founder2 &&
        first.creationInfo._1 == HEIGHT && second.creationInfo._1 == HEIGHT &&
        first.tokens.size == 0 && second.tokens.size == 0
      } else true
      sigmaProp(reward > 0L && remaining >= 0L &&
        INPUTS.size == 1 && INPUTS(0).id == SELF.id &&
        SELF.R4[Int].get == networkTag && HEIGHT == SELF.creationInfo._1 + 1 &&
        OUTPUTS.size == offset + 1 + foundersCount && continuation && founders &&
        miner.value == reward - 2L * founder && miner.propositionBytes == expectedMiner &&
        miner.creationInfo._1 == HEIGHT && miner.tokens.size == 0)
    }"""
    val result = new SigmaCompiler(48.toByte).compile(env, source)(new CompiletimeIRContext)
    ErgoTree.fromProposition(ErgoTree.ZeroHeader, result.buildTree.asInstanceOf[sigma.ast.Value[SSigmaProp.type]])
  }
}
