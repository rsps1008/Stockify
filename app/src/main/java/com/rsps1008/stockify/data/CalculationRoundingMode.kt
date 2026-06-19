package com.rsps1008.stockify.data

import kotlin.math.floor
import kotlin.math.roundToInt

object CalculationRoundingMode {
    const val ROUND = "ROUND"
    const val FLOOR = "FLOOR"

    fun normalize(value: String?): String {
        return when (value) {
            FLOOR -> FLOOR
            else -> ROUND
        }
    }

    fun apply(value: Double, mode: String): Double {
        return when (normalize(mode)) {
            FLOOR -> floor(value)
            else -> value.roundToInt().toDouble()
        }
    }

    fun applyCurrency(value: Double, market: String?, mode: String): Double {
        return if (StockMarket.isUs(market)) {
            when (normalize(mode)) {
                FLOOR -> floor(value * 100) / 100.0
                else -> (value * 100).roundToInt() / 100.0
            }
        } else {
            apply(value, mode)
        }
    }
}
