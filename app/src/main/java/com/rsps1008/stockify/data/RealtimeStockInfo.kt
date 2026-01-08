package com.rsps1008.stockify.data

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class RealtimeStockInfo(
    val currentPrice: Double,
    val change: Double,
    val changePercent: Double,
    val lastUpdated: Long = System.currentTimeMillis(),
    val limitState: LimitState = LimitState.NONE
)

enum class LimitState {
    LIMIT_UP,
    LIMIT_DOWN,
    NONE
}