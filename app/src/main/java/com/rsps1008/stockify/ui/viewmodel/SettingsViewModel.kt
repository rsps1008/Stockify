package com.rsps1008.stockify.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.rsps1008.stockify.data.CsvService
import com.rsps1008.stockify.data.CsvTransaction
import com.rsps1008.stockify.data.GoogleDriveService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockDataFetcher
import com.rsps1008.stockify.data.StockListRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SettingsViewModel(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    application: Application
) : AndroidViewModel(application) {

    private val stockDataFetcher = StockDataFetcher()
    private val stockListRepository = StockListRepository(application)
    private val csvService = CsvService()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _showImportConfirmDialog = MutableStateFlow(false)
    val showImportConfirmDialog: StateFlow<Boolean> = _showImportConfirmDialog.asStateFlow()

    private var importUri: Uri? = null
    private var importData: ByteArray? = null

    private val _googleSignInAccount = MutableStateFlow<GoogleSignInAccount?>(null)
    val googleSignInAccount: StateFlow<GoogleSignInAccount?> = _googleSignInAccount.asStateFlow()

    private val _onSignOut = MutableSharedFlow<Unit>()
    val onSignOut = _onSignOut.asSharedFlow()

    val refreshInterval: StateFlow<Int> = settingsDataStore.refreshIntervalFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 5)

    val yahooFetchInterval: StateFlow<Int> = settingsDataStore.yahooFetchIntervalFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 10)

    val lastStockListUpdateTime: StateFlow<Long?> = settingsDataStore.lastStockListUpdateTimeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val feeDiscount: StateFlow<Double> = settingsDataStore.feeDiscountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.28)

    val minFeeRegular: StateFlow<Int> = settingsDataStore.minFeeRegularFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1)

    val minFeeOddLot: StateFlow<Int> = settingsDataStore.minFeeOddLotFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1)

    val preDeductSellFees: StateFlow<Boolean> = settingsDataStore.preDeductSellFeesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    val theme: StateFlow<String> = settingsDataStore.themeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "System")

    init {
        val account = GoogleSignIn.getLastSignedInAccount(getApplication())
        // 在 init 和 handleSignInResult 中
        val driveScope = Scope(DriveScopes.DRIVE_APPDATA)

        if (account != null && GoogleSignIn.hasPermissions(account, driveScope)) {
            _googleSignInAccount.value = account
        } else {
            // 如果登入成功但沒權限，可以發出一個訊息提示使用者要勾選權限
            _googleSignInAccount.value = null
            if (account != null) _message.value = "請務必勾選 Google Drive 權限以進行備份"
        }
    }

    fun handleSignInResult(intent: Intent) {
        println("handleSignInResult")
        val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
        try {
            val account = task.getResult(ApiException::class.java)
            val driveScope = Scope(DriveScopes.DRIVE_APPDATA)
            val hasPermission = GoogleSignIn.hasPermissions(account, driveScope)

            println("Debug: account is null? ${account == null}, hasPermission? $hasPermission")

            if (account != null && GoogleSignIn.hasPermissions(account, driveScope)) {
                _googleSignInAccount.value = account
                _message.value = "Google 登入成功"
            } else {
                _googleSignInAccount.value = null
                _message.value = "Google 登入失敗，請授予 Google Drive 權限。"
            }
        } catch (e: ApiException) {
            _message.value = "Google 登入失敗: ${e.statusCode}"
        }
    }

    fun signOut() {
        viewModelScope.launch { 
            _onSignOut.emit(Unit) 
        }
    }

    fun onSignOutComplete() {
        _googleSignInAccount.value = null
        _message.value = "Google 登出成功"
    }

    fun backupToGoogleDrive() {
        viewModelScope.launch {
            _googleSignInAccount.value?.let { account ->
                _isLoading.value = true
                try {
                    val transactions = stockDao.getTransactionsWithStock().first()
                    val csvContent = withContext(Dispatchers.IO) {
                        ByteArrayOutputStream().use { 
                            csvService.export(transactions, it)
                            it.toByteArray()
                        }
                    }
                    val driveService = GoogleDriveService(getApplication(), account)
                    driveService.uploadBackup("stockify_backup.csv", csvContent).getOrThrow()
                    _message.value = "備份到 Google Drive 成功"
                } catch (e: Exception) {
                    _message.value = "備份到 Google Drive 失敗: ${e.message}"
                } finally {
                    _isLoading.value = false
                }
            } ?: run {
                _message.value = "請先登入 Google 帳號"
            }
        }
    }

    fun restoreFromGoogleDrive() {
        viewModelScope.launch {
            _googleSignInAccount.value?.let { account ->
                _isLoading.value = true
                try {
                    val driveService = GoogleDriveService(getApplication(), account)
                    val csvContent = driveService.restoreBackup("stockify_backup.csv").getOrThrow()
                    importData = csvContent
                    _showImportConfirmDialog.value = true
                } catch (e: Exception) {
                    _message.value = "從 Google Drive 還原失敗: ${e.message}"
                } finally {
                    _isLoading.value = false
                }
            } ?: run {
                _message.value = "請先登入 Google 帳號"
            }
        }
    }

    fun exportTransactions(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val transactions = stockDao.getTransactionsWithStock().first()
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        csvService.export(transactions, it)
                    }
                }
                _message.value = "匯出成功"
            } catch (e: Exception) {
                _message.value = "匯出失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onImportRequest(uri: Uri) {
        importUri = uri
        _showImportConfirmDialog.value = true
    }

    fun onImportConfirm(deleteOldData: Boolean) {
        _showImportConfirmDialog.value = false
        importUri?.let {
            performImportFromUri(it, deleteOldData)
        }
        importData?.let {
            performImportFromByteArray(it, deleteOldData)
        }
    }

    fun onImportCancel() {
        _showImportConfirmDialog.value = false
        importUri = null
        importData = null
    }

    private fun performImportFromUri(uri: Uri, deleteOldData: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (deleteOldData) {
                    deleteAllData()
                }

                val csvTransactions = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        csvService.import(it)
                    }
                } ?: emptyList()

                processImportedTransactions(csvTransactions)
                
            } catch (e: Exception) {
                _message.value = "匯入失敗: ${e.message}"
            } finally {
                _isLoading.value = false
                importUri = null
            }
        }
    }

    private fun performImportFromByteArray(data: ByteArray, deleteOldData: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (deleteOldData) {
                    deleteAllData()
                }

                val csvTransactions = withContext(Dispatchers.IO) {
                    ByteArrayInputStream(data).use { 
                        csvService.import(it)
                    }
                }

                processImportedTransactions(csvTransactions)

            } catch (e: Exception) {
                _message.value = "匯入失敗: ${e.message}"
            } finally {
                _isLoading.value = false
                importData = null
            }
        }
    }

    private suspend fun processImportedTransactions(transactions: List<CsvTransaction>) {
        transactions.forEach { csvTransaction ->
            var stock = stockDao.getStockByCode(csvTransaction.stockCode)
            if (stock == null) {
                val newStock = Stock(name = csvTransaction.stockName, code = csvTransaction.stockCode)
                stockDao.insertStock(newStock)
            }
            stockDao.insertTransaction(csvTransaction.transaction)
        }
        _message.value = "匯入成功，共 ${transactions.size} 筆紀錄"
    }

    fun setRefreshInterval(interval: Int) {
        viewModelScope.launch {
            settingsDataStore.setRefreshInterval(interval)
        }
    }

    fun setYahooFetchInterval(interval: Int) {
        viewModelScope.launch {
            settingsDataStore.setYahooFetchInterval(interval)
        }
    }

    fun setTheme(theme: String) {
        viewModelScope.launch {
            settingsDataStore.setTheme(theme)
        }
    }

    fun setFeeDiscount(discount: Double) {
        viewModelScope.launch {
            settingsDataStore.setFeeDiscount(discount)
        }
    }

    fun setMinFeeRegular(fee: Int) {
        viewModelScope.launch {
            settingsDataStore.setMinFeeRegular(fee)
        }
    }

    fun setMinFeeOddLot(fee: Int) {
        viewModelScope.launch {
            settingsDataStore.setMinFeeOddLot(fee)
        }
    }

    fun setPreDeductSellFees(preDeduct: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setPreDeductSellFees(preDeduct)
        }
    }

    fun deleteAllDataAndShowToast() {
        viewModelScope.launch {
            deleteAllData()
            _message.value = "所有資料已刪除"
        }
    }

    private suspend fun deleteAllData() {
        stockDao.deleteAllTransactions()
        stockDao.deleteAllStocks()
    }

    fun updateStockListFromTwse() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val stocks = stockDataFetcher.fetchStockList()
                // Save to json file
                stockListRepository.saveStocks(stocks)
                // And also save to Room database
                stockDao.insertStocks(stocks)
                settingsDataStore.setLastStockListUpdateTime(System.currentTimeMillis())
                _message.value = "股票列表更新成功！共 ${stocks.size} 筆"
            } catch (e: Exception) {
                _message.value = "更新失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }
}
