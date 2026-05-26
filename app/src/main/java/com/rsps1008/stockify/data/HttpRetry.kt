package com.rsps1008.stockify.data

import android.util.Log
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.coroutines.cancellation.CancellationException

suspend fun <T> retryOnTransientNetworkFailure(
    tag: String,
    stockCode: String,
    maxAttempts: Int = 2,
    initialDelayMs: Long = 250L,
    block: suspend () -> T
): T? {
    var attempt = 1

    while (attempt <= maxAttempts) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (attempt >= maxAttempts) {
                Log.e(
                    tag,
                    "Network request failed for $stockCode after $attempt attempts: ${e.message}",
                    e
                )
                return null
            }

            Log.w(
                tag,
                "Transient network error for $stockCode on attempt $attempt/$maxAttempts: ${e.message}; retrying"
            )
            delay(initialDelayMs * attempt)
        } catch (e: UnknownHostException) {
            if (attempt >= maxAttempts) {
                Log.e(
                    tag,
                    "Host resolution failed for $stockCode after $attempt attempts: ${e.message}",
                    e
                )
                return null
            }

            Log.w(
                tag,
                "Host resolution failed for $stockCode on attempt $attempt/$maxAttempts: ${e.message}; retrying"
            )
            delay(initialDelayMs * attempt)
        } catch (e: UnresolvedAddressException) {
            if (attempt >= maxAttempts) {
                Log.e(
                    tag,
                    "Unresolved address for $stockCode after $attempt attempts: ${e.message}",
                    e
                )
                return null
            }

            Log.w(
                tag,
                "Unresolved address for $stockCode on attempt $attempt/$maxAttempts: ${e.message}; retrying"
            )
            delay(initialDelayMs * attempt)
        } catch (e: ConnectException) {
            if (attempt >= maxAttempts) {
                Log.e(
                    tag,
                    "Connection failed for $stockCode after $attempt attempts: ${e.message}",
                    e
                )
                return null
            }

            Log.w(
                tag,
                "Connection failed for $stockCode on attempt $attempt/$maxAttempts: ${e.message}; retrying"
            )
            delay(initialDelayMs * attempt)
        } catch (e: SocketTimeoutException) {
            if (attempt >= maxAttempts) {
                Log.e(
                    tag,
                    "Timeout for $stockCode after $attempt attempts: ${e.message}",
                    e
                )
                return null
            }

            Log.w(
                tag,
                "Timeout for $stockCode on attempt $attempt/$maxAttempts: ${e.message}; retrying"
            )
            delay(initialDelayMs * attempt)
        }

        attempt++
    }

    return null
}
