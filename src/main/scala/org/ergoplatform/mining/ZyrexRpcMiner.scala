package org.ergoplatform.mining

import com.google.common.primitives.Ints
import io.circe.parser.parse
import io.circe.syntax._
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets
import scala.io.Source
import scorex.util.encode.Base16

/** CPU reference check of the external-miner RPC path. This is not a GPU miner. */
object ZyrexRpcMiner {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: ZyrexRpcMiner http://127.0.0.1:19558")
    val base = args(0).stripSuffix("/")
    def rpc(path: String, body: Option[String] = None): String = {
      val conn = new URL(base + path).openConnection().asInstanceOf[HttpURLConnection]
      conn.setConnectTimeout(5000)
      conn.setReadTimeout(30000)
      try {
        body.foreach { payload =>
          conn.setRequestMethod("POST")
          conn.setDoOutput(true)
          conn.setRequestProperty("Content-Type", "application/json")
          val output = conn.getOutputStream
          try output.write(payload.getBytes(StandardCharsets.UTF_8)) finally output.close()
        }
        require(conn.getResponseCode == 200, s"Mining RPC failed: HTTP ${conn.getResponseCode}")
        val source = Source.fromInputStream(conn.getInputStream, "UTF-8")
        try source.mkString finally source.close()
      } finally conn.disconnect()
    }
    val cursor = parse(rpc("/mining/candidate")).toTry.get.hcursor
    val msg = Base16.decode(cursor.get[String]("msg").toTry.get).get
    val height = cursor.get[Int]("h").toTry.get
    val target = cursor.get[BigInt]("b").toTry.get
    val pk = groupElemFromBytes(Base16.decode(cursor.get[String]("pk").toTry.get).get)
    val pow = new AutolykosPowScheme(32, 26)
    val solution = pow.checkNonces(4.toByte, Ints.toByteArray(height), msg, BigInt(1), BigInt(1),
      target, pow.calcN(4.toByte, height), 0L, 100000L).get.copy(pk = pk)
    rpc("/mining/solution", Some(solution.asJson.noSpaces))
    println(s"ZYREX_EXTERNAL_SOLUTION_ACCEPTED_HEIGHT=$height")
  }
}
