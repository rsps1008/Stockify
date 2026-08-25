package com.rsps1008.stockify

import com.rsps1008.stockify.data.GoogleDriveBackupBundle
import com.rsps1008.stockify.data.GoogleDriveBackupFile
import com.rsps1008.stockify.data.GoogleDriveBackupRestored
import com.rsps1008.stockify.data.GoogleDriveBackupSelectionSupport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class GoogleDriveBackupBundleTest {
    @Test
    fun newerLegacyOrderOverridesTheOrderEmbeddedInAnOlderBundle() {
        val bundleOrder = "bundle-order".toByteArray()
        val legacyOrder = "legacy-order".toByteArray()
        val selected = GoogleDriveBackupSelectionSupport.selectHoldingsOrder(
            bundleFile = GoogleDriveBackupFile("bundle".toByteArray(), modifiedAtMillis = 100L),
            restoredBundle = GoogleDriveBackupRestored(
                generation = "g1",
                transactionsCsv = ByteArray(0),
                accountsJson = ByteArray(0),
                holdingsOrderJson = bundleOrder
            ),
            legacyOrderFile = GoogleDriveBackupFile(legacyOrder, modifiedAtMillis = 101L)
        )

        assertArrayEquals(legacyOrder, selected)
    }

    @Test
    fun olderLegacyOrderDoesNotOverrideTheBundle() {
        val bundleOrder = "bundle-order".toByteArray()
        val selected = GoogleDriveBackupSelectionSupport.selectHoldingsOrder(
            bundleFile = GoogleDriveBackupFile("bundle".toByteArray(), modifiedAtMillis = 101L),
            restoredBundle = GoogleDriveBackupRestored(
                generation = "g1",
                transactionsCsv = ByteArray(0),
                accountsJson = ByteArray(0),
                holdingsOrderJson = bundleOrder
            ),
            legacyOrderFile = GoogleDriveBackupFile("legacy-order".toByteArray(), modifiedAtMillis = 100L)
        )

        assertArrayEquals(bundleOrder, selected)
    }

    @Test
    fun createAndRestorePreservesGenerationAndPayloads() {
        val transactions = "id,筆記\n1,\"第一行\n第二行\"".toByteArray()
        val accounts = "[{\"id\":1,\"name\":\"預設帳戶\"}]".toByteArray()
        val holdingsOrder = "{\"order\":[\"TW:2330\"]}".toByteArray()

        val bundle = GoogleDriveBackupBundle.create(
            transactionsCsv = transactions,
            accountsJson = accounts,
            holdingsOrderJson = holdingsOrder,
            generation = "generation-20260822-1",
            createdAtMillis = 123L
        )

        val restored = GoogleDriveBackupBundle.restore(bundle)

        assertEquals("generation-20260822-1", restored.generation)
        assertArrayEquals(transactions, restored.transactionsCsv)
        assertArrayEquals(accounts, restored.accountsJson)
        assertArrayEquals(holdingsOrder, restored.holdingsOrderJson)
    }

    @Test
    fun restoreRejectsPayloadChangedWithoutUpdatingManifest() {
        val original = GoogleDriveBackupBundle.create(
            transactionsCsv = "original".toByteArray(),
            accountsJson = "accounts".toByteArray(),
            holdingsOrderJson = "order".toByteArray(),
            generation = "generation-1",
            createdAtMillis = 123L
        )
        val tampered = replaceZipEntry(original, "transactions.csv", "tampered".toByteArray())

        try {
            GoogleDriveBackupBundle.restore(tampered)
            fail("checksum mismatch should reject the backup")
        } catch (e: IllegalArgumentException) {
            assertEquals("交易備份 checksum 不一致", e.message)
        }
    }

    @Test
    fun restoreRejectsAnOversizedCompressedEntryBeforeExpandingItFully() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("transactions.csv"))
            zip.write(ByteArray(8 * 1024 * 1024 + 1))
            zip.closeEntry()
        }

        assertThrows(IllegalArgumentException::class.java) {
            GoogleDriveBackupBundle.restore(output.toByteArray())
        }
    }

    private fun replaceZipEntry(bundle: ByteArray, name: String, replacement: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(bundle)).use { input ->
            ZipOutputStream(output).use { zip ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(if (entry.name == name) replacement else input.readBytes())
                    zip.closeEntry()
                    input.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}
