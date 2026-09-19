package com.rsps1008.stockify.data

internal const val DEFAULT_FEE_DISCOUNT = 0.28

internal fun effectiveFeeDiscount(
    accountId: Int,
    accounts: List<Account>,
    sharedFeeDiscount: Double
): Double = accounts.firstOrNull { it.id == accountId }?.feeDiscount ?: sharedFeeDiscount

internal fun accountFeeDiscounts(
    accounts: List<Account>,
    sharedFeeDiscount: Double
): Map<Int, Double> = accounts.associate { account ->
    account.id to (account.feeDiscount ?: sharedFeeDiscount)
}
