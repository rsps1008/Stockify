package com.rsps1008.stockify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rsps1008.stockify.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AssetBalanceStoreInstrumentedTest {
    @get:Rule val folder = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
    private val balances = AssetBalances(
        listOf(BankDeposit(1, "銀行", 12345.67), BankDeposit(2, "零餘額", 0.0)),
        listOf(Loan(1, "房貸", 99999.12))
    )

    @Test
    fun restoreIsAtomicAndPreservesNonAssetSettingsWhileInvalidInputChangesNothing() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val store = SettingsDataStore(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "atomic.preferences_pb") })
        val states = Channel<AssetBalances>(Channel.UNLIMITED)
        val collecting = launch { store.assetBalancesFlow.collect { states.send(it) } }
        suspend fun next() = withTimeout(5_000) { states.receive() }
        try {
            assertEquals(AssetBalances(), next())
            store.setActiveAccountId(3)
            store.setHoldingsOrder(listOf("TW:2330"))
            store.setRealtimeStockInfoCache(mapOf("TW:2330" to RealtimeStockInfo(100.0, 1.0, 1.0)))
            store.replaceAssetBalances(balances)
            assertEquals(balances, next())
            val replacement = AssetBalances(listOf(BankDeposit(4, "B", 42.56)), listOf(Loan(9, "L", 8.9)))
            store.replaceAssetBalances(AssetBalanceBackupCodec.decode(AssetBalanceBackupCodec.encode(replacement)))
            assertEquals(replacement, next())
            try {
                store.replaceAssetBalances(replacement.copy(loans = listOf(Loan(1, "invalid", -10.0))))
                fail("Invalid restore must fail")
            } catch (_: IllegalArgumentException) { }
            assertEquals(replacement, store.assetBalancesFlow.first())
            assertEquals(3, store.activeAccountIdFlow.first())
            assertEquals(listOf("TW:2330"), store.holdingsOrderFlow.first())
            assertEquals(100.0, store.realtimeStockInfoCacheFlow.first().getValue("TW:2330").currentPrice, 0.0)
            store.replaceAssetBalances(AssetBalances())
            assertEquals(AssetBalances(), next())
        } finally { collecting.cancel(); scope.cancel() }
    }

    @Test
    fun concurrentBalanceEditsKeepEveryRowAndOtherBalanceType() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val store = SettingsDataStore(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "edits.preferences_pb") })
        try {
            store.replaceAssetBalances(AssetBalances(loans = balances.loans))
            (1..30).map { index -> async(Dispatchers.Default) {
                store.updateAssetBalances { current ->
                    val id = nextAssetBalanceId(current.bankDeposits.map { it.id })
                    current.copy(bankDeposits = current.bankDeposits + BankDeposit(id, "B$index", index.toDouble()))
                }
            } }.awaitAll()
            val saved = store.assetBalancesFlow.first()
            assertEquals(30, saved.bankDeposits.size)
            assertEquals(30, saved.bankDeposits.map { it.id }.distinct().size)
            assertEquals(465.0, saved.bankDeposits.sumOf { it.amount }, 0.0)
            assertEquals(balances.loans, saved.loans)
        } finally { scope.cancel() }
    }

}
