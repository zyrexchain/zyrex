package org.ergoplatform

import scorex.util.encode.Base58
import scala.util.Try

/** Keep the upstream address format and checksums, with strict Zyrex network prefixes. */
class ZyrexAddressEncoder(prefix: Byte) extends ErgoAddressEncoder(prefix) {
  require(Set[Byte](48, 64, 80).contains(prefix), "Unknown Zyrex address prefix")

  private val brand: String = if (prefix == 80.toByte) "" else "ZRX"

  override def toString(address: ErgoAddress): String = brand + super.toString(address)

  override def fromString(encoded: String): Try[ErgoAddress] = Try {
    require(brand.isEmpty || encoded.startsWith(brand), "Zyrex addresses must start with ZRX")
    if (brand.isEmpty) encoded else encoded.substring(brand.length)
  }.flatMap(Base58.decode).flatMap { bytes =>
    Try {
      require(bytes.length >= 6, "Address is too short")
      val addressType = (bytes.head & 0xff) - (prefix & 0xff)
      require(addressType >= 1 && addressType <= 3, "Address belongs to a different network")
      val (payload, checksum) = bytes.splitAt(bytes.length - 4)
      require(ErgoAddressEncoder.hash256(payload).take(4).sameElements(checksum), "Invalid address checksum")
      require(addressType != 1 || payload.length == 34, "Invalid P2PK public key length")
      val normalized = addressType.toByte +: payload.tail
      val legacy = Base58.encode(normalized ++ ErgoAddressEncoder.hash256(normalized).take(4))
      ErgoAddressEncoder.Mainnet.fromString(legacy).get match {
        case addr: P2PKAddress => P2PKAddress(addr.pubkey)(this)
        case addr: Pay2SHAddress => new Pay2SHAddress(addr.contentBytes)(this)
        case addr: Pay2SAddress => Pay2SAddress(addr.script)(this)
      }
    }
  }
}

object ZyrexAddressEncoder {
  /** Legacy codecs remain available to historical tests and upstream helper libraries. */
  def apply(prefix: Byte): ErgoAddressEncoder =
    if (Set[Byte](48, 64, 80).contains(prefix)) new ZyrexAddressEncoder(prefix)
    else ErgoAddressEncoder(prefix)
}
