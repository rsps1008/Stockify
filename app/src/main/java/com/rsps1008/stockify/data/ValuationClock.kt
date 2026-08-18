package com.rsps1008.stockify.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

internal const val VALUATION_REFRESH_INTERVAL_MILLIS = 60_000L

internal fun valuationClockFlow(
    clock: () -> Long = System::currentTimeMillis,
    intervalMillis: Long = VALUATION_REFRESH_INTERVAL_MILLIS
): Flow<Long> {
    require(intervalMillis > 0L) { "intervalMillis must be positive" }
    return flow {
        while (currentCoroutineContext().isActive) {
            emit(clock())
            delay(intervalMillis)
        }
    }
}
