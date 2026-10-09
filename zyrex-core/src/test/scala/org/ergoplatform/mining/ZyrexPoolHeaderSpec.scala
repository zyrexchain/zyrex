package org.ergoplatform.mining

import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.ergoplatform.modifiers.history.header.{HeaderSerializer, HeaderWithoutPow}
import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.crypto.authds.ADDigest
import scorex.crypto.hash.Digest32
import scorex.util.bytesToId
import scorex.util.encode.Base16

/** Native serialization oracle for the independent Python pool header encoder. */
class ZyrexPoolHeaderSpec extends ErgoCorePropertyTest {
  property("pool message fixtures match the actual native header serializer") {
    val fixture = ZyrexPoolHeaderVectors.load()
    val vectors = fixture.hcursor.get[Vector[Json]]("vectors").right.get
    val pow = new AutolykosPowScheme(32, 26)
    vectors.size should be >= 10
    vectors.foreach { vector =>
      val cursor = vector.hcursor
      val header = ZyrexPoolHeaderVectors.header(cursor.downField("header").focus.get)
      val bytes = HeaderSerializer.bytesWithoutPow(header)
      Base16.encode(bytes) shouldBe cursor.get[String]("bytesWithoutPow").right.get
      Base16.encode(pow.msgByHeader(header)) shouldBe cursor.get[String]("msg").right.get
    }
    vectors.map(_.hcursor.downField("header").get[Int]("version").right.get)
      .toSet shouldBe Set(2, 3, 4, 5)
  }

  property("the pool oracle retains the independently published Autolykos message") {
    val vector = ZyrexPoolHeaderVectors.load().hcursor.get[Vector[Json]]("vectors")
      .right.get.find(_.hcursor.get[String]("name").right.get == "upstream-autolykos-v2")
      .get
    val header = ZyrexPoolHeaderVectors.header(
      vector.hcursor.downField("header").focus.get)
    Base16.encode(new AutolykosPowScheme(32, 26).msgByHeader(header)) shouldBe
      "548c3e602a8f36f8f2738f5f643b02425038044d98543a51cabaa9785e7e864f"
  }
}

/** Regenerate native bytes/messages with test:runMain, preserving fixture inputs. */
object ZyrexPoolHeaderVectors {
  private val root: Path = Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(path => Files.exists(path.resolve("pool/header_vectors.json")))
    .getOrElse(throw new IllegalStateException("Run from the project checkout"))
  private val fixture: Path = root.resolve("pool/header_vectors.json")

  def load(): Json =
    parse(new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8))
      .fold(error => throw error, identity)

  def header(json: Json): HeaderWithoutPow = {
    val cursor = json.hcursor
    def raw(name: String): Array[Byte] =
      Base16.decode(cursor.get[String](name).right.get).get
    HeaderWithoutPow(
      cursor.get[Int]("version").right.get.toByte,
      bytesToId(raw("parentId")),
      Digest32 @@ raw("adProofsRoot"),
      ADDigest @@ raw("stateRoot"),
      Digest32 @@ raw("transactionsRoot"),
      cursor.get[Long]("timestamp").right.get,
      cursor.get[Long]("nBits").right.get,
      cursor.get[Int]("height").right.get,
      Digest32 @@ raw("extensionHash"),
      raw("votes"),
      raw("unparsedBytes")
    )
  }

  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "This oracle only updates its checked-in public fixture")
    val input = load()
    val pow = new AutolykosPowScheme(32, 26)
    val vectors = input.hcursor.get[Vector[Json]]("vectors").right.get.map { vector =>
      val native = header(vector.hcursor.downField("header").focus.get)
      val fields = vector.asObject.get
        .add("bytesWithoutPow", Json.fromString(Base16.encode(
          HeaderSerializer.bytesWithoutPow(native))))
        .add("msg", Json.fromString(Base16.encode(pow.msgByHeader(native))))
      Json.fromJsonObject(fields)
    }
    val output = Json.fromJsonObject(
      input.asObject.get.add("vectors", Json.fromValues(vectors)))
    Files.write(fixture, (output.spaces2 + "\n").getBytes(StandardCharsets.UTF_8))
    println(s"Generated ${vectors.size} native pool header vectors")
  }
}
