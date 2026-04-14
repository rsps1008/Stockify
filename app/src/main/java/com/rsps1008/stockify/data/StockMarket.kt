package com.rsps1008.stockify.data

object StockMarket {
    const val TW = "TW"
    const val US = "US"

    fun normalize(value: String?): String {
        return when (value?.trim()?.uppercase()) {
            US -> US
            TW -> TW
            else -> TW
        }
    }

    fun isUs(value: String?): Boolean = normalize(value) == US

    fun isTw(value: String?): Boolean = normalize(value) == TW

    fun inferFromCode(stockCode: String): String {
        val trimmed = stockCode.trim()
        return when {
            trimmed.isBlank() -> TW
            trimmed.any { it.isLetter() } && trimmed.none { it.isDigit() } -> US
            trimmed.contains('.') || trimmed.contains('-') -> US
            else -> TW
        }
    }
}
