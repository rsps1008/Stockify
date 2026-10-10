package com.rsps1008.stockify

import com.rsps1008.stockify.data.AutomaticCloudBackupGate
import com.rsps1008.stockify.data.CloudBackupUploadLocks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBackupConcurrencyTest {
    @Test
    fun overlappingAutomaticBackupSkipsAndCancellationReleasesGate() = runBlocking {
        val gate = AutomaticCloudBackupGate()
        val entered = CompletableDeferred<Unit>()
        val first = launch {
            gate.runIfIdle {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        var duplicateRan = false
        assertTrue(gate.runIfIdle { duplicateRan = true; false })
        assertFalse(duplicateRan)
        first.cancelAndJoin()
        var nextRan = false
        assertTrue(gate.runIfIdle { nextRan = true; true })
        assertTrue(nextRan)
    }

    @Test
    fun sameAccountFileWaitsWhileOtherAccountsAndFilesProceed() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            CloudBackupUploadLocks.withFileLock("USER@example.com", "backup.zip") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var secondRan = false
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            CloudBackupUploadLocks.withFileLock("user@example.com", "backup.zip") {
                secondRan = true
            }
        }
        assertFalse(secondRan)
        assertTrue(CloudBackupUploadLocks.withFileLock("other@example.com", "backup.zip") { true })
        assertTrue(CloudBackupUploadLocks.withFileLock("user@example.com", "other.zip") { true })
        release.complete(Unit)
        first.join()
        second.await()
        assertTrue(secondRan)
    }
}
