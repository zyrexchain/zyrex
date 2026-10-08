package org.ergoplatform.tools

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.nio.file.attribute.PosixFilePermissions
import org.ergoplatform.{P2PKAddress, ZyrexAddressEncoder}
import org.ergoplatform.sdk.wallet.secrets.{DerivationPath, ExtendedSecretKey}
import org.ergoplatform.wallet.mnemonic.Mnemonic
import scorex.util.encode.Base16

/** Generate recoverable TESTNET wallets. The backup never belongs in Git. */
object ZyrexTestKeys {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: ZyrexTestKeys private-backup.json")
    val output = Paths.get(args(0))
    require(!Files.exists(output), "Refusing to overwrite an existing key backup")
    val path = "m/44'/429'/0'/0/0"
    val accounts = Seq("founder-1", "founder-2", "pool").map { role =>
      val mnemonic = new Mnemonic("english", 256).generate.get
      val seed = Mnemonic.toSeed(mnemonic)
      val root = ExtendedSecretKey.deriveMasterKey(seed, false)
      val key = root.derive(DerivationPath.fromEncoded(path).get)
      val result = Json.obj(
        "role" -> Json.fromString(role), "network" -> Json.fromString("testnet"),
        "mnemonic" -> Json.fromString(mnemonic.toStringUnsecure),
        "derivationPath" -> Json.fromString(path),
        "privateKeyHex" -> Json.fromString(Base16.encode(key.keyBytes)),
        "publicKeyHex" -> Json.fromString(Base16.encode(key.publicKey.keyBytes)),
        "address" -> Json.fromString(P2PKAddress(key.publicKey.key)(ZyrexAddressEncoder(64.toByte)).toString))
      mnemonic.erase()
      java.util.Arrays.fill(seed, 0.toByte)
      key.zeroSecret()
      root.zeroSecret()
      result
    }
    Files.createDirectories(output.toAbsolutePath.getParent)
    Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    Files.write(output, Json.obj("wallets" -> Json.arr(accounts: _*)).spaces2.getBytes(StandardCharsets.UTF_8))
    println("Testnet wallet backup saved privately; no secret material printed")
  }
}
