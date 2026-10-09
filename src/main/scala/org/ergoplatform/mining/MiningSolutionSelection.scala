package org.ergoplatform.mining

import org.ergoplatform.mining.CandidateGenerator.Candidate
import org.ergoplatform.modifiers.ErgoFullBlock

import scala.util.{Failure, Try}

/** Bind a solution to its advertised work before validating its native PoW. */
object MiningSolutionSelection {
  final case class Rejected(reason: String) extends IllegalArgumentException(reason)

  def complete(current: Option[Candidate],
               previous: Option[Candidate],
               solution: AutolykosSolution,
               requestedMessage: Option[Array[Byte]],
               powScheme: AutolykosPowScheme): Try[ErgoFullBlock] = {
    val candidates = current.toSeq ++ previous.toSeq
    def validate(candidate: Candidate): Try[ErgoFullBlock] = {
      Try(CandidateGenerator.completeBlock(candidate.candidateBlock, solution)).flatMap { block =>
        powScheme.validate(block.header).map(_ => block)
      }
    }
    requestedMessage match {
      case Some(message) if message.length != 32 =>
        Failure(Rejected("Requested work message must contain exactly 32 bytes"))
      case Some(message) =>
        candidates.find(_.externalVersion.msg.sameElements(message)) match {
          case Some(candidate) =>
            validate(candidate).recoverWith {
              case error => Failure(Rejected(s"Invalid solution for requested work: ${error.getMessage}"))
            }
          case None => Failure(Rejected("Requested work is stale or unknown"))
        }
      case None =>
        // Preserve the legacy current-then-previous selection for unbound miners.
        candidates.foldLeft[Try[ErgoFullBlock]](Failure(Rejected("No cached mining candidate"))) {
          case (success, _) if success.isSuccess => success
          case (_, candidate) => validate(candidate)
        }
    }
  }
}
