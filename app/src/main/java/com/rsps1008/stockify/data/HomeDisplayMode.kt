package com.rsps1008.stockify.data

object HomeDisplayMode {
    const val TW = "TW"
    const val US = "US"
    const val COMBINED = "COMBINED"

    fun normalize(value: String?): String {
        return when (value?.trim()?.uppercase()) {
            TW -> TW
            US -> US
            COMBINED -> COMBINED
            else -> COMBINED
        }
    }

    fun label(value: String?): String {
        return when (normalize(value)) {
            TW -> "純台股"
            US -> "純美股"
            COMBINED -> "台股 + 美股"
            else -> "台股 + 美股"
        }
    }
}
