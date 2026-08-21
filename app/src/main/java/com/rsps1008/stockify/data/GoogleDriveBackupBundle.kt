package com.rsps1008.stockify.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

data class GoogleDriveBackupRestored(
    val generation: String,
    val transactionsCsv: ByteArray,
    val accountsJson: ByteArray,
    val holdingsOrderJson: ByteArray
)

object GoogleDriveBackupBundle {
    const val FILE_NAME = "stockify_backup_bundle.zip"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val TRANSACTIONS_ENTRY = "transactions.csv"
    private const val ACCOUNTS_ENTRY = "accounts.json"
    private const val HOLDINGS_ORDER_ENTRY = "holdings_order.json"
    private const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Manifest(
        val version: Int,
        val generation: String,
        val createdAtMillis: Long,
        val transactionsSha256: String,
        val accountsSha256: String,
        val holdingsOrderSha256: String
    )

    fun create(
        transactionsCsv: ByteArray,
        accountsJson: ByteArray,
        holdingsOrderJson: ByteArray,
        generation: String = UUID.randomUUID().toString(),
        createdAtMillis: Long = System.currentTimeMillis()
    ): ByteArray {
        require(generation.isNotBlank()) { "備份 generation 不可空白" }
        val manifest = Manifest(
            version = VERSION,
            generation = generation,
            createdAtMillis = createdAtMillis,
            transactionsSha256 = sha256(transactionsCsv),
            accountsSha256 = sha256(accountsJson),
            holdingsOrderSha256 = sha256(holdingsOrderJson)
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            writeEntry(zip, MANIFEST_ENTRY, json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            writeEntry(zip, TRANSACTIONS_ENTRY, transactionsCsv)
            writeEntry(zip, ACCOUNTS_ENTRY, accountsJson)
            writeEntry(zip, HOLDINGS_ORDER_ENTRY, holdingsOrderJson)
        }
        return output.toByteArray()
    }

    fun restore(bundle: ByteArray): GoogleDriveBackupRestored {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bundle)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "備份 bundle 不可包含目錄" }
                require(entries.put(entry.name, zip.readBytes()) == null) {
                    "備份 bundle 含有重複檔案"
                }
                zip.closeEntry()
            }
        }

        val manifest = entries[MANIFEST_ENTRY]
            ?.toString(Charsets.UTF_8)
            ?.let { json.decodeFromString<Manifest>(it) }
            ?: error("備份 bundle 缺少 manifest")
        require(manifest.version == VERSION) { "不支援的備份 bundle 版本" }
        require(manifest.generation.isNotBlank()) { "備份 bundle 缺少 generation" }

        val transactions = entries.requireEntry(TRANSACTIONS_ENTRY)
        val accounts = entries.requireEntry(ACCOUNTS_ENTRY)
        val holdingsOrder = entries.requireEntry(HOLDINGS_ORDER_ENTRY)
        require(sha256(transactions) == manifest.transactionsSha256) { "交易備份 checksum 不一致" }
        require(sha256(accounts) == manifest.accountsSha256) { "帳戶備份 checksum 不一致" }
        require(sha256(holdingsOrder) == manifest.holdingsOrderSha256) { "持股排序備份 checksum 不一致" }

        return GoogleDriveBackupRestored(
            generation = manifest.generation,
            transactionsCsv = transactions,
            accountsJson = accounts,
            holdingsOrderJson = holdingsOrder
        )
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: ByteArray) {
        zip.putNextEntry(java.util.zip.ZipEntry(name))
        zip.write(content)
        zip.closeEntry()
    }

    private fun Map<String, ByteArray>.requireEntry(name: String): ByteArray =
        this[name] ?: error("備份 bundle 缺少 $name")

    private fun sha256(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
