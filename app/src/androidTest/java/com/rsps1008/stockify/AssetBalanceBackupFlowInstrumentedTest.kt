package com.rsps1008.stockify

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rsps1008.stockify.data.*
import com.rsps1008.stockify.ui.screens.HoldingInfo
import com.rsps1008.stockify.ui.screens.HoldingsUiState
import com.rsps1008.stockify.ui.screens.TransactionUiState
import com.rsps1008.stockify.ui.viewmodel.AssetOverviewViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AssetBalanceBackupFlowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val folder = TemporaryFolder(instrumentation.targetContext.cacheDir)
    private val repository = object : StockRepository {
        override fun getHoldings(): Flow<HoldingsUiState> = flowOf(HoldingsUiState())
        override fun getHoldingInfo(stockCode: String, accountId: Int, market: String): Flow<HoldingInfo?> = flowOf(null)
        override fun getTransactionsForStock(stockCode: String, accountId: Int, market: String): Flow<List<TransactionUiState>> = flowOf(emptyList())
    }

    @Test
    fun backupPreviewCancelConfirmAndBadFileUseActualResolverWithoutAffectingAccounts() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataFile = File(folder.root, "flow.preferences_pb")
        val settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = scope) { dataFile })
        val model = AssetOverviewViewModel(settings, repository)
        val owner = ViewModelStore().apply { put("asset", model) }
        val resolver = instrumentation.targetContext.contentResolver
        suspend fun action(block: () -> Unit) {
            instrumentation.runOnMainSync(block)
            withTimeout(10_000) { model.isBusy.first { !it } }
        }
        try {
            settings.setActiveAccountId(7)
            var saved = false
            action { model.saveBankDeposit(null, "存款", 123.45) { saved = true } }
            assertTrue(saved)
            action { model.saveLoan(null, "貸款", 67.89) }
            val original = settings.assetBalancesFlow.first()
            val file = File(folder.root, "backup.json")
            action { model.exportBackup(resolver, Uri.fromFile(file)) }
            assertEquals("存款與貸款備份成功", model.message.value)
            assertEquals(original, AssetBalanceBackupCodec.decode(file.readText()))

            val replacement = AssetBalances(listOf(BankDeposit(9, "新存款", 999.99)), emptyList())
            var uploaded: AssetBalances? = null
            action {
                model.backupToGoogleDrive { name, bytes, mime ->
                    assertEquals("stockify_asset_balances.json", name)
                    assertEquals("application/json", mime)
                    uploaded = AssetBalanceBackupCodec.read(bytes.inputStream())
                }
            }
            assertEquals(original, uploaded)
            action { model.previewGoogleDriveRestore { replacement } }
            assertEquals(replacement, model.restorePreview.value)
            assertEquals(original, settings.assetBalancesFlow.first())
            instrumentation.runOnMainSync { model.dismissRestore() }
            assertNull(model.restorePreview.value)
            action { model.previewGoogleDriveRestore { error("網路中斷") } }
            assertNull(model.restorePreview.value)
            assertTrue(model.message.value!!.contains("網路中斷"))
            action { model.previewGoogleDriveRestore { AssetBalances(listOf(BankDeposit(1, "錯誤", Double.NaN))) } }
            assertNull(model.restorePreview.value)
            assertEquals(original, settings.assetBalancesFlow.first())
            action { model.backupToGoogleDrive { _, _, _ -> error("上傳失敗") } }
            assertTrue(model.message.value!!.contains("上傳失敗"))

            file.writeText(AssetBalanceBackupCodec.encode(replacement))
            action { model.previewRestore(resolver, Uri.fromFile(file)) }
            assertEquals(replacement, model.restorePreview.value)
            assertEquals(original, settings.assetBalancesFlow.first())
            instrumentation.runOnMainSync { model.dismissRestore() }
            assertNull(model.restorePreview.value)
            assertEquals(original, settings.assetBalancesFlow.first())

            action { model.previewGoogleDriveRestore { replacement } }
            action { model.confirmRestore(); model.confirmRestore() }
            assertNull(model.restorePreview.value)
            assertEquals("存款與貸款還原成功", model.message.value)
            assertEquals(replacement, settings.assetBalancesFlow.first())
            assertEquals(7, settings.activeAccountIdFlow.first())

            file.writeText("{not-json")
            action { model.previewRestore(resolver, Uri.fromFile(file)) }
            assertNull(model.restorePreview.value)
            assertTrue(model.message.value!!.startsWith("操作失敗"))
            assertEquals(replacement, settings.assetBalancesFlow.first())
            action { model.exportBackup(resolver, Uri.fromFile(folder.root)) }
            assertTrue(model.message.value!!.startsWith("操作失敗"))

            scope.coroutineContext[Job]!!.cancelAndJoin()
            val reopenedScope = CoroutineScope(Dispatchers.IO + Job())
            try {
                val reopened = SettingsDataStore(PreferenceDataStoreFactory.create(scope = reopenedScope) { dataFile })
                assertEquals(replacement, reopened.assetBalancesFlow.first())
                assertEquals(7, reopened.activeAccountIdFlow.first())
            } finally { reopenedScope.coroutineContext[Job]!!.cancelAndJoin() }
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
