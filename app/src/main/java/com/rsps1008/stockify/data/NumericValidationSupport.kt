package com.rsps1008.stockify.data

internal fun Double?.isFinitePositive(): Boolean = this != null && isFinite() && this > 0.0

internal fun Double?.finiteOrZero(): Double = takeIf { it?.isFinite() == true } ?: 0.0
