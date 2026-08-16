package com.rsps1008.stockify.data.dividend

import kotlinx.serialization.Serializable

@Serializable
data class DividendInfoCacheEntry(
    val cashDividend: Double? = null,
    val cashDividendDate: String? = null,
    val stockDividend: Double? = null,
    val stockDividendDate: String? = null,
    val lastLocalCashDividend: Double? = null,
    val lastLocalCashDividendDate: String? = null,
    val lastLocalStockDividend: Double? = null,
    val lastLocalStockDividendDate: String? = null,
    val lastLocalAccountId: Int? = null,
    val lastFetchedDate: String? = null
)
