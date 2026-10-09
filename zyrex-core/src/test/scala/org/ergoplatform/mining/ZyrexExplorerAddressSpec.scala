package org.ergoplatform.mining

import org.ergoplatform.{Pay2SHAddress, ZyrexAddressEncoder}
import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.util.encode.Base16

/** Independent native-SDK oracle for the Python explorer's P2SH fixtures. */
class ZyrexExplorerAddressSpec extends ErgoCorePropertyTest {
  property("canonical P2SH address scripts match explorer fixtures") {
    val encoder = ZyrexAddressEncoder(64.toByte)
    val vectors = Seq(
      (
        (0 until 24).map(_.toByte).toArray,
        "ZRX3znEGfcaKmXJEkwow5UQZSqtULDCuDwES5n9k6D8",
        "00ea02d193b4cbe4e37e0e040004300e18" +
          "000102030405060708090a0b0c0d0e0f1011121314151617d4087e"
      ),
      (
        Array.fill(24)(0xff.toByte),
        "ZRX43QwdtCpNXcocZ1e6Z2SobUkzuhjpsSPMJZXtRJa",
        "00ea02d193b4cbe4e37e0e040004300e18" +
          "ffffffffffffffffffffffffffffffffffffffffffffffffd4087e"
      )
    )
    vectors.foreach { case (hash, expectedAddress, expectedScript) =>
      val address = new Pay2SHAddress(hash)(encoder)
      address.toString shouldBe expectedAddress
      Base16.encode(address.script.bytes) shouldBe expectedScript
      encoder.fromString(expectedAddress).get.script.bytes.toSeq shouldBe
        address.script.bytes.toSeq
    }
  }
}
