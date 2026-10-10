package com.rsps1008.stockify.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class AutomaticCloudBackupGate {
    private val mutex = Mutex()

    suspend fun runIfIdle(block: suspend () -> Boolean): Boolean {
        if (!mutex.tryLock()) return true
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

internal object CloudBackupUploadLocks {
    private val locks = ConcurrentHashMap<Pair<String, String>, Mutex>()

    suspend fun <T> withFileLock(email: String, fileName: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(email.lowercase(Locale.ROOT) to fileName) { Mutex() }
        return mutex.withLock { block() }
    }
}
