package com.rsps1008.stockify.data

object TransactionCostSupport {
    fun minimumTaiwanSellFee(
        shares: Double,
        minFeeRegular: Double,
        minFeeOddLot: Double
    ): Double {
        return if (shares % 1_000.0 == 0.0) minFeeRegular else minFeeOddLot
    }
}
