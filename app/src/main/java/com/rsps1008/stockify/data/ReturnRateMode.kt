package com.rsps1008.stockify.data

enum class ReturnRateMode(val key: String) {
    REMAINING_POSITION("REMAINING_POSITION"),
    CUMULATIVE_INVESTMENT("CUMULATIVE_INVESTMENT"),
    XIRR("XIRR");

    companion object {
        fun normalize(raw: String?): ReturnRateMode {
            return values().firstOrNull { it.key == raw || it.name == raw } ?: REMAINING_POSITION
        }
    }
}
