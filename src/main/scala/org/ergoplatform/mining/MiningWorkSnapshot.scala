package org.ergoplatform.mining

import io.circe.{Encoder, Json}
import io.circe.syntax._
import org.ergoplatform.mining.CandidateGenerator.Candidate
import scorex.util.encode.Base16

/** Atomic work-cache information for clients opting into candidate details. */
final case class MiningWorkSnapshot(
  candidate: Candidate,
  previous: Option[Candidate],
  nodeSolved: Boolean
) {
  private val current = candidate.externalVersion

  val parentId: String =
    AutolykosPowScheme.derivedHeaderFields(candidate.candidateBlock.parentOpt)._1

  val activeMessages: Seq[String] = {
    val compatible = previous.filter { old =>
      val work = old.externalVersion
      AutolykosPowScheme.derivedHeaderFields(old.candidateBlock.parentOpt)._1 == parentId &&
        work.h == current.h && work.pk == current.pk && work.b == current.b
    }
    (Seq(current) ++ compatible.map(_.externalVersion)).map(work => Base16.encode(work.msg)).distinct
  }
}

object MiningWorkSnapshot {
  implicit val encoder: Encoder[MiningWorkSnapshot] = Encoder.instance { snapshot =>
    snapshot.candidate.externalVersion.asJson.deepMerge(Json.obj(
      "workVersion" -> Json.fromInt(1),
      "parentId" -> Json.fromString(snapshot.parentId),
      "activeMessages" -> snapshot.activeMessages.asJson,
      "nodeSolved" -> Json.fromBoolean(snapshot.nodeSolved)
    ))
  }
}
