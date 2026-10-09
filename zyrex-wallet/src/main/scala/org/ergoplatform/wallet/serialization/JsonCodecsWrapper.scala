package org.ergoplatform.wallet.serialization

import org.ergoplatform.sdk.JsonCodecs

/**
  * JSON Codecs provided as singleton package, not trait.
  * Could be useful for Java applications willing to use JSON codecs for blockchain objects.
  */
object JsonCodecsWrapper extends JsonCodecs
