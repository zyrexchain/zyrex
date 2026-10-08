package org.ergoplatform.mining

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.ergoplatform.{P2PKAddress, ZyrexAddressEncoder}
import org.ergoplatform.sdk.SecretString
import org.ergoplatform.sdk.wallet.secrets.{DerivationPath, ExtendedSecretKey}
import org.ergoplatform.tools.ZyrexTestKeys
import org.ergoplatform.utils.ErgoCorePropertyTest
import org.ergoplatform.wallet.mnemonic.Mnemonic
import scorex.util.encode.Base16

class ZyrexTestKeysSpec extends ErgoCorePropertyTest {
  property("private testnet backups restore the exact HD keys and ZRX addresses") {
    val directory = Files.createTempDirectory("zyrex-wallet-backup-")
    val output = directory.resolve("wallets.json")
    try {
      ZyrexTestKeys.main(Array(output.toString))
      Files.getPosixFilePermissions(output) shouldBe PosixFilePermissions.fromString("rw-------")
      val data = io.circe.parser.parse(new String(Files.readAllBytes(output), "UTF-8")).toTry.get
      val accounts = data.hcursor.get[Seq[io.circe.Json]]("wallets").toTry.get
      accounts.size shouldBe 3
      val addresses = accounts.map { account =>
        val c = account.hcursor
        val words = c.get[String]("mnemonic").toTry.get
        words.split(" ").length shouldBe 24
        val mnemonic = SecretString.create(words)
        val seed = Mnemonic.toSeed(mnemonic)
        val root = ExtendedSecretKey.deriveMasterKey(seed, false)
        val key = root.derive(DerivationPath.fromEncoded(c.get[String]("derivationPath").toTry.get).get)
        try {
          Base16.encode(key.keyBytes) shouldBe c.get[String]("privateKeyHex").toTry.get
          Base16.encode(key.publicKey.keyBytes) shouldBe c.get[String]("publicKeyHex").toTry.get
          val address = c.get[String]("address").toTry.get
          P2PKAddress(key.publicKey.key)(ZyrexAddressEncoder(64.toByte)).toString shouldBe address
          address
        } finally {
          key.zeroSecret()
          root.zeroSecret()
          mnemonic.erase()
          java.util.Arrays.fill(seed, 0.toByte)
        }
      }
      addresses.distinct.size shouldBe 3
      addresses.foreach { address =>
        address.startsWith("ZRX") shouldBe true
        ZyrexAddressEncoder(64.toByte).fromString(address).isSuccess shouldBe true
      }
      intercept[IllegalArgumentException] { ZyrexTestKeys.main(Array(output.toString)) }
    } finally {
      Files.deleteIfExists(output)
      Files.delete(directory)
    }
  }
}
