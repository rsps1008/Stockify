package com.rsps1008.stockify.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

@Serializable
private data class HoldingsOrderBackup(
    val version: Int = 1,
    val order: List<String>,
    val realizedOrder: List<String> = emptyList()
)

data class HoldingsOrderBackupData(
    val order: List<String>,
    val realizedOrder: List<String>
)

class HoldingsOrderBackupService {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun export(order: List<String>, realizedOrder: List<String>, outputStream: OutputStream) {
        val backup = HoldingsOrderBackup(
            order = order.sanitizeHoldingsOrder(),
            realizedOrder = realizedOrder.sanitizeHoldingsOrder()
        )
        outputStream.write(json.encodeToString(backup).toByteArray(Charsets.UTF_8))
    }

    fun exportToBytes(order: List<String>, realizedOrder: List<String>): ByteArray {
        return json.encodeToString(
            HoldingsOrderBackup(
                order = order.sanitizeHoldingsOrder(),
                realizedOrder = realizedOrder.sanitizeHoldingsOrder()
            )
        )
            .toByteArray(Charsets.UTF_8)
    }

    fun import(inputStream: InputStream): HoldingsOrderBackupData {
        return import(inputStream.readBytes())
    }

    fun import(bytes: ByteArray): HoldingsOrderBackupData {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) return HoldingsOrderBackupData(emptyList(), emptyList())

        if (text.startsWith("{")) {
            val backup = json.decodeFromString<HoldingsOrderBackup>(text)
            return HoldingsOrderBackupData(
                order = backup.order.sanitizeHoldingsOrder(),
                realizedOrder = backup.realizedOrder.sanitizeHoldingsOrder()
            )
        } else {
            return HoldingsOrderBackupData(
                order = text.lineSequence().toList().sanitizeHoldingsOrder(),
                realizedOrder = emptyList()
            )
        }
    }

    private fun List<String>.sanitizeHoldingsOrder(): List<String> {
        return map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
