package org.ergoplatform.nodeView.mempool

import org.ergoplatform.utils.ErgoTestHelpers
import org.ergoplatform.utils.generators.ValidBlocksGenerators._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ZyrexLegacyFixtureSpec extends AnyFlatSpec with ErgoTestHelpers with Matchers {
  import org.ergoplatform.utils.ErgoCoreTestConstants._
  import org.ergoplatform.utils.ErgoNodeTestConstants._

  it should "generate a native-valid legacy first block without bypassing input scripts" in {
    val (state, boxes) = createUtxoState(settings)
    chainSettings.monetary shouldBe settings.chainSettings.monetary
    chainSettings.zyrex shouldBe None
    settings.chainSettings.zyrex shouldBe None
    val block = validFullBlock(None, state, boxes)
    state.applyModifier(block, None)(_ => ()).isSuccess shouldBe true
  }
}
