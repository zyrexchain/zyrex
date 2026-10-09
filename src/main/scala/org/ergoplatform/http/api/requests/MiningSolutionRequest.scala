package org.ergoplatform.http.api.requests

import io.circe.{Decoder, DecodingFailure}
import org.ergoplatform.mining.AutolykosSolution
import scorex.util.encode.Base16

/** Optional work binding for external miners; the PoW solution format is unchanged. */
case class MiningSolutionRequest(solution: AutolykosSolution, msg: Option[Array[Byte]])

object MiningSolutionRequest {
  implicit val decoder: Decoder[MiningSolutionRequest] = Decoder.instance { cursor =>
    val message = if (cursor.value.asObject.exists(_.contains("msg"))) {
      cursor.downField("msg").as[String].flatMap { encoded =>
        if (encoded.length != 64) {
          Left(DecodingFailure("msg must contain exactly 32 bytes of hexadecimal data", cursor.history))
        } else {
          Base16.decode(encoded).toEither.left.map { _ =>
            DecodingFailure("msg must contain exactly 32 bytes of hexadecimal data", cursor.history)
          }.map(bytes => Some(bytes))
        }
      }
    } else {
      Right(None)
    }
    for {
      solution <- AutolykosSolution.jsonDecoder(cursor)
      msg <- message
    } yield MiningSolutionRequest(solution, msg)
  }
}
