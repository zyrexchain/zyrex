package org.ergoplatform.http.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import io.circe.syntax._
import org.ergoplatform.{ErgoBoxCandidate, Input}
import org.ergoplatform.http.api.{ApiCodecs, TransactionsApiRoute}
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, ErgoTransactionSerializer}
import org.ergoplatform.settings.Constants.TrueTree
import org.ergoplatform.utils.Stubs
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ZyrexFeeQuoteApiSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest
  with Stubs with ApiCodecs with FailFastCirceSupport {
  import org.ergoplatform.utils.ErgoCoreTestConstants._
  import org.ergoplatform.utils.ErgoNodeTestConstants._

  private val transaction = ErgoTransaction(
    IndexedSeq(Input(utxoState.takeBoxes(1).head.id, emptyProverResult)),
    IndexedSeq(new ErgoBoxCandidate(1000000000L, TrueTree, creationHeight = 0)))
  private val route = TransactionsApiRoute(utxoReadersRef, nodeViewRef, settings).route

  it should "return versioned string-based quotes for a public size hint" in {
    Get("/transactions/feeQuote?txSize=123&waitTime=1") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val cursor = responseAs[Json].hcursor
      cursor.get[Int]("quoteVersion") shouldBe Right(1)
      cursor.get[Int]("txSize") shouldBe Right(123)
      cursor.get[String]("feeNano") shouldBe Right(settings.nodeSettings.minimalFeeAmount.toString)
      cursor.get[String]("minimumFeeNano") shouldBe Right(settings.nodeSettings.minimalFeeAmount.toString)
      cursor.get[Int]("maxTransactionSize") shouldBe Right(settings.nodeSettings.maxTransactionSize)
      cursor.get[String]("basis") shouldBe Right("minimum")
      cursor.get[String]("feeScriptHex") shouldBe Right(settings.chainSettings.monetary.feeProposition.bytesHex)
      cursor.get[String]("txId").isLeft shouldBe true
    }
  }

  it should "retain the legacy numeric fee response" in {
    Get("/transactions/getFee?txSize=123&waitTime=1") ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json].as[Long] shouldBe Right(settings.nodeSettings.minimalFeeAmount)
    }
  }

  it should "quote exact native signed bytes and bind the transaction id while ignoring a forged JSON size" in {
    val json = transaction.asJson.deepMerge(Json.obj("size" -> Json.fromInt(1)))
    Post("/transactions/feeQuote", json) ~> route ~> check {
      status shouldBe StatusCodes.OK
      val cursor = responseAs[Json].hcursor
      cursor.get[Int]("txSize") shouldBe Right(ErgoTransactionSerializer.toBytes(transaction).length)
      cursor.get[String]("txId") shouldBe Right(transaction.id)
      cursor.get[String]("paidFeeNano") shouldBe Right("0")
      cursor.get[String]("feeScriptHex") shouldBe Right(settings.chainSettings.monetary.feeProposition.bytesHex)
    }
  }

  it should "derive the paid fee from the configured fee script and reject integer overflow" in {
    val feeTree = settings.chainSettings.monetary.feeProposition
    val paid = transaction.copy(outputCandidates = transaction.outputCandidates :+
      new ErgoBoxCandidate(1234567L, feeTree, creationHeight = 0))
    Post("/transactions/feeQuote", paid.asJson) ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json].hcursor.get[String]("paidFeeNano") shouldBe Right("1234567")
    }
    val excessive = transaction.copy(outputCandidates = IndexedSeq(
      new ErgoBoxCandidate(Long.MaxValue, feeTree, creationHeight = 0),
      new ErgoBoxCandidate(Long.MaxValue, feeTree, creationHeight = 0)))
    Post("/transactions/feeQuote", excessive.asJson) ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "reject zero, negative, excessive and malformed size or wait parameters" in {
    Seq("txSize=0", "txSize=-1", "txSize=98305", "waitTime=0", "waitTime=-1",
      "waitTime=61", "waitTime=invalid", "txSize=invalid").foreach { parameter =>
      Get("/transactions/feeQuote?" + parameter) ~> Route.seal(route) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
    Post("/transactions/feeQuote?waitTime=0", transaction.asJson) ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "enforce the configured serialized transaction size limit" in {
    val limitedSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(maxTransactionSize = 1))
    val limitedRoute = TransactionsApiRoute(utxoReadersRef, nodeViewRef, limitedSettings).route
    Post("/transactions/feeQuote", transaction.asJson) ~> limitedRoute ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "authenticate signed quotes just like wallet operations without hiding public size hints" in {
    val authenticated = settings.copy(scorexSettings = settings.scorexSettings.copy(
      restApi = settings.scorexSettings.restApi.copy(apiKeyHash = Some("00" * 32))))
    val authenticatedRoute = TransactionsApiRoute(utxoReadersRef, nodeViewRef, authenticated).route
    Post("/transactions/feeQuote", transaction.asJson) ~> Route.seal(authenticatedRoute) ~> check {
      status shouldBe StatusCodes.Forbidden
    }
    Get("/transactions/feeQuote?txSize=123") ~> authenticatedRoute ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "bound signed JSON bodies and reject malformed signed JSON" in {
    Post("/transactions/feeQuote", Json.obj("padding" -> Json.fromString("x" * (2 * 1024 * 1024)))) ~>
      Route.seal(route) ~> check {
      status.intValue() shouldBe 413
    }
    Post("/transactions/feeQuote", Json.obj("invalid" -> Json.True)) ~> Route.seal(route) ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "reject invalid wait-time divisions safely" in {
    Get("/transactions/waitTime?txSize=0") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
    }
    Get("/transactions/waitTime?fee=-1&txSize=100") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }
}
