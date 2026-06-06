package com.rsps1008.stockify.data

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

fun formatShareInputValue(value: Double): String {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

fun formatShareCount(value: Double): String {
    val format = NumberFormat.getNumberInstance(Locale.getDefault())
    format.minimumFractionDigits = 0
    format.maximumFractionDigits = 6
    return format.format(value)
}
