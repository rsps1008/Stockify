package com.rsps1008.stockify.data.dividend

import kotlinx.serialization.Serializable

@Serializable
data class YahooDividendCacheEntry(
    val cashDividend: Double? = null,
    val cashDividendDate: String? = null,
    val stockDividend: Double? = null,
    val stockDividendDate: String? = null,
    val lastFetchedDate: String? = null,
    val lastFetchedTimeMillis: Long? = null,
    val requestSequence: Long? = null
)

typealias DividendInfoCacheEntry = YahooDividendCacheEntry
