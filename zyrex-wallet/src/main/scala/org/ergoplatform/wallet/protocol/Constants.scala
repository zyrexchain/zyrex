package org.ergoplatform.wallet.protocol

object Constants {

  val HashLength: Int = 32

  val BlocksPerHour = 30

  val BlocksPerDay: Int = BlocksPerHour * 24

  val BlocksPerWeek: Int = BlocksPerDay * 7

  val BlocksPerMonth: Int = BlocksPerDay * 30

  val BlocksPerYear: Int = BlocksPerDay * 365

  //For how many blocks a box could be put into the state with no paying storage rent.
  // Retained 1,051,200-block threshold: 730 nominal days at Zyrex's 60-second target.
  val StoragePeriod: Int = 4 * BlocksPerYear

  val StorageContractCost: Long = 50

  val StorageIndexVarId: Byte = Byte.MaxValue
}
