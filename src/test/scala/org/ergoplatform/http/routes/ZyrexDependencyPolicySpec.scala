package org.ergoplatform.http.routes

import com.typesafe.config.ConfigFactory
import java.io.File
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Configuration mitigation does not erase the upstream vulnerable version match. */
class ZyrexDependencyPolicySpec extends AnyFlatSpec with Matchers {
  private val application = ConfigFactory.parseFile(new File("src/main/resources/application.conf"))
  private val http2 = "akka.http.server.preview.enable-http2"

  it should "explicitly disable HTTP/2 in the packaged application configuration" in {
    application.hasPath(http2) shouldBe true
    application.getBoolean(http2) shouldBe false
  }

  it should "retain the HTTP/2 mitigation in every shipped network profile" in {
    Seq("devnet", "testnet", "mainnet").foreach { profile =>
      val effective = ConfigFactory.parseFile(new File(s"src/main/resources/$profile.conf"))
        .withFallback(application).withFallback(ConfigFactory.defaultReference()).resolve()
      effective.getBoolean(http2) shouldBe false
    }
  }
}
