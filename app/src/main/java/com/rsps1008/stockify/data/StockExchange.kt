package com.rsps1008.stockify.data

object StockExchange {
    const val UNKNOWN = ""
    const val LISTED = "上市"
    const val OTC = "上櫃"
    const val EMERGING = "興櫃"

    fun normalize(value: String?): String {
        val text = value?.trim().orEmpty()
        return when {
            text.contains("興櫃") -> EMERGING
            text.contains("上櫃") -> OTC
            text.contains("上市") -> LISTED
            else -> UNKNOWN
        }
    }

    fun isEmerging(value: String?): Boolean = normalize(value) == EMERGING
}
