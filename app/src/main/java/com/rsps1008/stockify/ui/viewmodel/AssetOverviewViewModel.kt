package com.rsps1008.stockify.ui.viewmodel

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.BankDeposit
import com.rsps1008.stockify.data.Loan
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.data.AssetBalances
import com.rsps1008.stockify.data.AssetBalanceBackupCodec
import com.rsps1008.stockify.data.nextAssetBalanceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.rsps1008.stockify.ui.screens.AssetStockValue
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AssetOverviewUiState(
    val taiwanStockValue: Double = 0.0,
    val usStockValue: Double = 0.0,
    val stockValues: List<AssetStockValue> = emptyList(),
    val bankDeposits: List<BankDeposit> = emptyList(),
    val loans: List<Loan> = emptyList()
) {
    val totalBankDeposit: Double
        get() = bankDeposits.sumOf { it.amount }

    val totalLoan: Double
        get() = loans.sumOf { it.amount }

    val grossAssets: Double
        get() = taiwanStockValue + usStockValue + totalBankDeposit

    val netAssets: Double
        get() = grossAssets - totalLoan
}

class AssetOverviewViewModel(
    private val settingsDataStore: SettingsDataStore,
    stockRepository: StockRepository
) : ViewModel() {

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _restorePreview = MutableStateFlow<AssetBalances?>(null)
    val restorePreview = _restorePreview.asStateFlow()

    val uiState: StateFlow<AssetOverviewUiState> = combine(
        stockRepository.getHoldings(),
        settingsDataStore.assetBalancesFlow
    ) { holdings, bankData ->
        AssetOverviewUiState(
            taiwanStockValue = holdings.taiwanMarketValue,
            usStockValue = holdings.usMarketValue,
            stockValues = holdings.assetStockValues,
            bankDeposits = bankData.bankDeposits,
            loans = bankData.loans
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AssetOverviewUiState()
    )

    fun saveBankDeposit(id: Long?, name: String, amount: Double, onSaved: () -> Unit = {}) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank() || !amount.isFinite() || amount < 0.0) return

        performOperation(onSuccess = onSaved) {
            settingsDataStore.updateAssetBalances { balances ->
                val current = balances.bankDeposits
                val updated = if (id == null) {
                    current + BankDeposit(nextAssetBalanceId(current.map { it.id }), normalizedName, amount)
                } else {
                    require(current.any { it.id == id }) { "這筆存款已不存在，請重新開啟" }
                    current.map { deposit ->
                        if (deposit.id == id) deposit.copy(name = normalizedName, amount = amount) else deposit
                    }
                }
                balances.copy(bankDeposits = updated)
            }
        }
    }

    fun deleteBankDeposit(id: Long) {
        performOperation {
            settingsDataStore.updateAssetBalances { it.copy(bankDeposits = it.bankDeposits.filterNot { row -> row.id == id }) }
        }
    }

    fun saveLoan(id: Long?, name: String, amount: Double, onSaved: () -> Unit = {}) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank() || !amount.isFinite() || amount < 0.0) return

        performOperation(onSuccess = onSaved) {
            settingsDataStore.updateAssetBalances { balances ->
                val current = balances.loans
                val updated = if (id == null) {
                    current + Loan(nextAssetBalanceId(current.map { it.id }), normalizedName, amount)
                } else {
                    require(current.any { it.id == id }) { "這筆貸款已不存在，請重新開啟" }
                    current.map { loan ->
                        if (loan.id == id) loan.copy(name = normalizedName, amount = amount) else loan
                    }
                }
                balances.copy(loans = updated)
            }
        }
    }

    fun deleteLoan(id: Long) {
        performOperation {
            settingsDataStore.updateAssetBalances { it.copy(loans = it.loans.filterNot { row -> row.id == id }) }
        }
    }

    fun exportBackup(resolver: ContentResolver, uri: Uri) = performOperation("存款與貸款備份成功") {
        val content = AssetBalanceBackupCodec.encode(settingsDataStore.readAssetBalancesForBackup())
        resolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: error("無法寫入備份檔案")
    }

    fun exportBackupToDownloads(resolver: ContentResolver) = performOperation("備份已儲存至 Download/Stockify") {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("Android 9 以下需要檔案選擇器才能儲存備份")
        }
        val content = AssetBalanceBackupCodec.encode(settingsDataStore.readAssetBalancesForBackup()).toByteArray(Charsets.UTF_8)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "stockify_asset_balances_${System.currentTimeMillis()}.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Stockify")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("無法建立備份檔案")
        try {
            resolver.openOutputStream(uri)?.use { it.write(content) } ?: error("無法寫入備份檔案")
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0) {
                "無法完成備份檔案"
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun previewRestore(resolver: ContentResolver, uri: Uri) = performOperation {
        _restorePreview.value = null
        _restorePreview.value = resolver.openInputStream(uri)?.use(AssetBalanceBackupCodec::read)
            ?: error("無法讀取備份檔案")
    }

    fun confirmRestore() {
        val preview = _restorePreview.value ?: return
        performOperation("存款與貸款還原成功", onSuccess = { _restorePreview.value = null }) {
            settingsDataStore.replaceAssetBalances(preview)
        }
    }

    fun dismissRestore() { if (!_isBusy.value) _restorePreview.value = null }
    fun clearMessage() { _message.value = null }
    fun showMessage(message: String) { _message.value = message }

    private fun performOperation(
        successMessage: String? = null,
        onSuccess: () -> Unit = {},
        operation: suspend () -> Unit
    ) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
                onSuccess()
                _message.value = successMessage
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "操作失敗：${e.message ?: "請稍後再試"}"
            } finally {
                _isBusy.value = false
            }
        }
    }
}
