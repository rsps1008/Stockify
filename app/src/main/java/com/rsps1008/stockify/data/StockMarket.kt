package com.rsps1008.stockify.data

object StockMarket {
    const val TW = "TW"
    const val US = "US"

    private val usTickerPattern = Regex("[A-Za-z]+(?:[.-][A-Za-z]+)?")

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
            // Taiwan ETFs and other securities may use mixed codes such as
            // 00981A. Only an unambiguously US-shaped ticker is inferred as US;
            // a code containing digits remains Taiwan unless its market is
            // explicitly supplied by the caller.
            trimmed.matches(usTickerPattern) -> US
            else -> TW
        }
    }
}
