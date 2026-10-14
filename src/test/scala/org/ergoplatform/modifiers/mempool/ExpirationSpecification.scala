package org.ergoplatform.modifiers.mempool

import org.ergoplatform.nodeView.state.{ErgoStateContext, VotingData}
import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCorePropertyTest
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, Input}
import org.scalatest.Assertion
import scorex.util.encode.Base16
import sigma.Colls
import sigma.ast.{ErgoTree, ShortConstant}
import sigma.interpreter.{ContextExtension, ProverResult}
import sigma.serialization.ErgoTreeSerializer
import sigmastate.helpers.TestingHelpers._
import org.ergoplatform.settings.Constants.TrueTree

class ExpirationSpecification extends ErgoCorePropertyTest {
  import org.ergoplatform.utils.ErgoCoreTestConstants._
  import org.ergoplatform.utils.ErgoNodeTestConstants._
  import org.ergoplatform.utils.generators.ErgoCoreTransactionGenerators._
  import org.ergoplatform.utils.NodeViewTestOps._

  type Height = Int

  private implicit val verifier: ErgoInterpreter = ErgoInterpreter(parameters)

  def injectScript(box: ErgoBox, script: ErgoTree): ErgoBox = {
    testBox(box.value,
      script,
      box.creationHeight,
      box.additionalTokens.toArray.toSeq,
      box.additionalRegisters,
      transactionId = box.transactionId,
      boxIndex = box.index)
  }

  def constructTest(from: ErgoBox,
                    heightDelta: Int,
                    outsConstructor: Height => IndexedSeq[ErgoBoxCandidate],
                    expectedValidity: Boolean): Assertion = {
    val in = Input(from.id,
      ProverResult(Array.emptyByteArray, ContextExtension(Map(Constants.StorageIndexVarId -> ShortConstant(0)))))
    val h: Int = from.creationHeight + Constants.StoragePeriod + heightDelta
    val oc = outsConstructor(h).map(c => updateHeight(c, h))
    val tx = ErgoTransaction(inputs = IndexedSeq(in), dataInputs = IndexedSeq(), outputCandidates = oc)

    // Storage rules need the native transaction context, not a randomly invalid block extension.
    val parent = org.ergoplatform.utils.generators.ErgoCoreGenerators.defaultHeaderGen.sample.get
      .copy(height = h - 1, timestamp = defaultTimestamp, version = parameters.blockVersion,
        votes = Array.fill(3)(0.toByte))
    val inContext = new ErgoStateContext(Seq(parent), None, genesisStateDigest, parameters, validationSettingsNoIl,
      VotingData.empty)(settings.chainSettings)
    val updContext = inContext.upcoming(defaultMinerPkPoint, parent.timestamp + 1, parent.nBits,
      Array.fill(3)(0.toByte), emptyVSUpdate, parent.version)
    updContext.currentHeight shouldBe h

    val bs = ErgoTransactionSerializer.toBytes(tx)
    ErgoTransactionSerializer.parseBytes(bs) shouldBe tx
    tx.statelessValidity().isSuccess shouldBe true
    tx.statefulValidity(IndexedSeq(from), emptyDataBoxes, updContext).isSuccess shouldBe expectedValidity
  }

  property("successful spending w. same value") {
    forAll(unspendableErgoBoxGen()) { from =>
      constructTest(from, 0, _ => IndexedSeq(from), expectedValidity = true)
    }
  }

  property("successful spending w. invalid ergotree") {
    forAll(unspendableErgoBoxGen()) { from =>
      // invalid (unparseable) ergo tree
      val etString = "0e1631393039303063646462363930366462363530336665"
      val et = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(Base16.decode(etString).get)
      val modified = injectScript(from, et)
      constructTest(modified, 0, _ => IndexedSeq(modified), expectedValidity = true)
    }
  }

  property("successful spending w. max spending") {
    forAll(unspendableErgoBoxGen()) { from =>
      constructTest(from, 0, h => {
        val fee = Math.min(parameters.storageFeeFactor * from.bytes.length, from.value)
        val feeBoxCandidate = new ErgoBoxCandidate(fee, TrueTree, creationHeight = h)
        IndexedSeq(changeValue(from, -fee), Some(feeBoxCandidate)).flatten
      }, expectedValidity = true)
    }
  }

  property("unsuccessful spending due too big storage fee charged") {
    forAll(unspendableErgoBoxGen(parameters.storageFeeFactor * 100 + 1, Long.MaxValue)) { from =>
      constructTest(from, 0, h => {
        val fee = Math.min(parameters.storageFeeFactor * from.bytes.length + 1, from.value)
        val feeBoxCandidate = new ErgoBoxCandidate(fee, TrueTree, creationHeight = h)
        IndexedSeq(changeValue(from, -fee), Some(feeBoxCandidate)).flatten
      }, expectedValidity = false)
    }
  }

  property("unsuccessful spending when more time passed than storage period and charged more than K*storagePeriod") {
    forAll(unspendableErgoBoxGen(parameters.storageFeeFactor * 100 + 1, Long.MaxValue)) { from =>
      constructTest(from, 1, h => {
        val fee = Math.min(parameters.storageFeeFactor * from.bytes.length + 1, from.value)
        val feeBoxCandidate = new ErgoBoxCandidate(fee, TrueTree, creationHeight = h)

        IndexedSeq(changeValue(from, -fee), Some(feeBoxCandidate)).flatten
      }, expectedValidity = false)
    }
  }

  property("too early spending") {
    forAll(unspendableErgoBoxGen()) { from =>
      constructTest(from, -1, h => {
        val fee = Math.min(parameters.storageFeeFactor * from.bytes.length, from.value)
        val feeBoxCandidate = new ErgoBoxCandidate(fee, TrueTree, creationHeight = h)
        IndexedSeq(changeValue(from, -fee), Some(feeBoxCandidate)).flatten
      }, expectedValidity = false)
    }
  }

  property("storage age boundaries remain checked at a native voting epoch boundary") {
    val source = unspendableErgoBoxGen().sample.get
    val epoch = settings.chainSettings.voting.votingLength
    val targetHeight = (Constants.StoragePeriod / epoch + 1) * epoch
    val from = testBox(source.value, source.ergoTree, targetHeight - Constants.StoragePeriod,
      source.additionalTokens.toArray.toSeq, source.additionalRegisters)
    targetHeight % epoch shouldBe 0
    constructTest(from, 0, _ => IndexedSeq(from), expectedValidity = true)
    constructTest(from, -1, _ => IndexedSeq(from), expectedValidity = false)
  }

  property("script changed spending w. same value") {
    forAll(unspendableErgoBoxGen()) { from =>
      val out = new ErgoBoxCandidate(from.value, TrueTree, from.creationHeight + 1, from.additionalTokens)
      constructTest(from, 0, _ => IndexedSeq(out), expectedValidity = false)
    }
  }

  property("script changed tokens w. same value") {
    forAll(unspendableErgoBoxGen()) { from =>
      whenever(from.additionalTokens.nonEmpty) {
        val out = new ErgoBoxCandidate(from.value, from.ergoTree, from.creationHeight + 1, Colls.emptyColl)
        constructTest(from, 0, _ => IndexedSeq(out), expectedValidity = false)
      }
    }
  }

  property("script changed register w. same value") {
    forAll(unspendableErgoBoxGen()) { from =>
      whenever(from.additionalRegisters.get(ErgoBox.R4).nonEmpty) {
        val out = new ErgoBoxCandidate(from.value, from.ergoTree, from.creationHeight + 1, from.additionalTokens)
        constructTest(from, 0, _ => IndexedSeq(out), expectedValidity = false)
      }
    }
  }

  property("spending of whole coin when its value no more than storage fee") {
    val out2 = ergoBoxGenNoProp.sample.get
    val minValue = out2.value + 1

    forAll(unspendableErgoBoxGen(minValue, Long.MaxValue)) { from =>
      val outcome = from.value <= from.bytes.length * parameters.storageFeeFactor
      val out1 = new ErgoBoxCandidate(from.value - minValue, TrueTree, creationHeight = from.creationHeight + 1)
      constructTest(from, 0, _ => IndexedSeq(out1, out2), expectedValidity = outcome)
    }
  }

  property("destructing the whole box when its value no more than storage fee") {
    forAll(unspendableErgoBoxGen(maxValue = parameters.storageFeeFactor)) { from =>
      val out = new ErgoBoxCandidate(from.value, TrueTree, creationHeight = from.creationHeight + 1)
      constructTest(from, 0, _ => IndexedSeq(out), expectedValidity = true)
    }
  }

}
