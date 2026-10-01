package com.rsps1008.stockify

import com.rsps1008.stockify.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AssetBalanceBackupTest {
    private val balances = AssetBalances(
        listOf(BankDeposit(1, "台幣存款\n備用金 \"A\"", 12345.67), BankDeposit(2, "零餘額", 0.0)),
        listOf(Loan(1, "房貸", 99999.12))
    )

    @Test
    fun utf8RoundTripPreservesNamesDecimalsIdsAndZeroAmounts() {
        val content = AssetBalanceBackupCodec.encode(balances)
        assertEquals(balances, AssetBalanceBackupCodec.read(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))))
        assertEquals(balances, AssetBalanceBackupCodec.decode("\uFEFF$content"))
        assertEquals(AssetBalances(), AssetBalanceBackupCodec.decode(AssetBalanceBackupCodec.encode(AssetBalances())))
    }

    @Test
    fun wrongBackupTypesVersionsMissingListsAndMalformedFilesAreRejected() {
        val content = AssetBalanceBackupCodec.encode(balances)
        listOf("", "{}", "[]", "date,stockCode,price", "{broken", content.dropLast(1),
            content.replace("stockify.asset-balances", "stockify.accounts"),
            content.replace("\"version\": 1", "\"version\": 2"),
            """{"format":"stockify.asset-balances","version":1,"bankDeposits":[]}""",
            content.replace("\"loans\"", "\"accounts\"")
        ).forEach { rejected { AssetBalanceBackupCodec.decode(it) } }
    }

    @Test
    fun invalidRowsAndOverflowCannotBeExportedOrRestored() {
        listOf(
            balances.copy(bankDeposits = listOf(BankDeposit(0, "銀行", 1.0))),
            balances.copy(loans = listOf(Loan(-1, "銀行", 1.0))),
            balances.copy(loans = listOf(Loan(1, " ", 1.0))),
            balances.copy(loans = listOf(Loan(1, "銀行", -1.0))),
            balances.copy(loans = listOf(Loan(1, "銀行", Double.NaN))),
            balances.copy(loans = listOf(Loan(1, "銀行", Double.POSITIVE_INFINITY))),
            balances.copy(bankDeposits = balances.bankDeposits + balances.bankDeposits.first()),
            AssetBalances(listOf(BankDeposit(1, "A", Double.MAX_VALUE)), listOf(Loan(1, "B", Double.MAX_VALUE)))
        ).forEach { rejected { AssetBalanceBackupCodec.encode(it) } }
        val content = AssetBalanceBackupCodec.encode(balances)
        rejected { AssetBalanceBackupCodec.decode(content.replace("12345.67", "-1.0")) }
        rejected { AssetBalanceBackupCodec.decode(content.replace("12345.67", "1e999")) }
    }

    @Test
    fun boundedReadRejectsOversizedAndInvalidUtf8Files() {
        rejected { AssetBalanceBackupCodec.read(ByteArrayInputStream(ByteArray(AssetBalanceBackupCodec.MAX_BYTES + 1) { 32 })) }
        rejected { AssetBalanceBackupCodec.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))) }
    }

    @Test
    fun amountEditingPreservesDecimalsAndIdsDoNotOverflow() {
        listOf(123.45, 0.0, 1.23456789, 100000000.0).forEach {
            assertEquals(it, formatAssetInputAmount(it).toDouble(), 0.0)
        }
        assertEquals("123.45", formatAssetInputAmount(123.45))
        assertEquals(2L, nextAssetBalanceId(listOf(Long.MAX_VALUE, 1)))
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid backup must be rejected") }
        catch (_: IllegalArgumentException) { }
        catch (_: java.nio.charset.CharacterCodingException) { }
    }
}
