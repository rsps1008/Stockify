package com.rsps1008.stockify.data

internal const val DEFAULT_FEE_DISCOUNT = 0.28

internal data class AccountFeeSettings(
    val feeDiscount: Double,
    val minFeeRegular: Int,
    val minFeeOddLot: Int
)

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

internal fun accountFeeSettings(
    accounts: List<Account>,
    sharedFeeDiscount: Double,
    sharedMinFeeRegular: Int,
    sharedMinFeeOddLot: Int
): Map<Int, AccountFeeSettings> = accounts.associate { account ->
    account.id to AccountFeeSettings(
        feeDiscount = account.feeDiscount ?: sharedFeeDiscount,
        minFeeRegular = account.minFeeRegular ?: sharedMinFeeRegular,
        minFeeOddLot = account.minFeeOddLot ?: sharedMinFeeOddLot
    )
}

internal fun effectiveFeeSettings(
    accountId: Int,
    accounts: List<Account>,
    shared: AccountFeeSettings
): AccountFeeSettings = accounts.firstOrNull { it.id == accountId }?.let { account ->
    AccountFeeSettings(
        feeDiscount = account.feeDiscount ?: shared.feeDiscount,
        minFeeRegular = account.minFeeRegular ?: shared.minFeeRegular,
        minFeeOddLot = account.minFeeOddLot ?: shared.minFeeOddLot
    )
} ?: shared
