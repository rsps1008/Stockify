package com.rsps1008.stockify.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

data class AssetBalances(
    val bankDeposits: List<BankDeposit> = emptyList(),
    val loans: List<Loan> = emptyList()
)

@Serializable
private data class AssetBalanceBackup(
    val format: String,
    val version: Int,
    val bankDeposits: List<BankDeposit>,
    val loans: List<Loan>
)

object AssetBalanceBackupCodec {
    const val FILE_NAME = "stockify_asset_balances.json"
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val FORMAT = "stockify.asset-balances"
    private val json = Json { prettyPrint = true }

    fun encode(balances: AssetBalances): String {
        validate(balances)
        val content = json.encodeToString(AssetBalanceBackup(FORMAT, 1, balances.bankDeposits, balances.loans))
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "存款與貸款備份不可超過 2 MB" }
        return content
    }

    fun decode(content: String): AssetBalances {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "備份檔不可超過 2 MB" }
        val backup = try {
            json.decodeFromString<AssetBalanceBackup>(content.removePrefix("\uFEFF"))
        } catch (_: Exception) {
            throw IllegalArgumentException("請選擇完整的存款與貸款備份檔")
        }
        require(backup.format == FORMAT) { "這不是存款與貸款備份檔" }
        require(backup.version == 1) { "不支援此備份版本" }
        return AssetBalances(backup.bankDeposits, backup.loans).also(::validate)
    }

    fun read(input: InputStream): AssetBalances {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_BYTES) { "備份檔不可超過 2 MB" }
        }
        val bytes = output.toByteArray()
        return decode(bytes.decodeToString(throwOnInvalidSequence = true))
    }

    fun validate(balances: AssetBalances) {
        require(balances.bankDeposits.size + balances.loans.size <= 10_000) { "備份筆數不可超過 10,000 筆" }
        fun validateRows(rows: List<Triple<Long, String, Double>>) {
            require(rows.map { it.first }.distinct().size == rows.size) { "備份含重複識別碼" }
            require(rows.all { (id, name, amount) ->
                id > 0 && name.isNotBlank() && amount.isFinite() && amount >= 0.0
            }) { "名稱、識別碼或金額無效；金額須為非負有限數值" }
            require(rows.sumOf { it.third }.isFinite()) { "備份總金額超出範圍" }
        }
        validateRows(balances.bankDeposits.map { Triple(it.id, it.name, it.amount) })
        validateRows(balances.loans.map { Triple(it.id, it.name, it.amount) })
        require((balances.bankDeposits.sumOf { it.amount } + balances.loans.sumOf { it.amount }).isFinite()) {
            "備份總金額超出範圍"
        }
    }
}

internal fun nextAssetBalanceId(ids: List<Long>): Long {
    val maximum = ids.maxOrNull() ?: 0L
    if (maximum < Long.MAX_VALUE) return maximum + 1L
    val used = ids.toHashSet()
    return generateSequence(1L) { it + 1L }.first { it !in used }
}

internal fun formatAssetInputAmount(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
