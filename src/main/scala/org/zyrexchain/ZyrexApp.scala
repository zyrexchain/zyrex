package org.zyrexchain

/** Branded entry point for the node, preserving the protocol implementation ABI. */
object ZyrexApp {
  def main(args: Array[String]): Unit = org.ergoplatform.ErgoApp.main(args)
}
