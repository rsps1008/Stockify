package com.rsps1008.stockify.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import com.rsps1008.stockify.data.PdfHoldingImportService
import com.rsps1008.stockify.data.PdfStockImportPreview
import com.rsps1008.stockify.data.PdfStockImportPreviewItem
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockDataFetcher
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.StockTransaction
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
import kotlin.math.roundToInt

class SettingsViewModel(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    application: Application,
    private val realtimeStockDataService: RealtimeStockDataService
) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "SettingsViewModel"
    }

    private val stockDataFetcher = StockDataFetcher()
    private val stockListRepository = StockListRepository(application)
    private val csvService = CsvService()
    private val pdfHoldingImportService = PdfHoldingImportService()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _showImportConfirmDialog = MutableStateFlow(false)
    val showImportConfirmDialog: StateFlow<Boolean> = _showImportConfirmDialog.asStateFlow()

    private var importUri: Uri? = null
    private var importData: ByteArray? = null
    private var pdfImportUri: Uri? = null

    private val _googleSignInAccount = MutableStateFlow<GoogleSignInAccount?>(null)
    val googleSignInAccount: StateFlow<GoogleSignInAccount?> = _googleSignInAccount.asStateFlow()

    private val _onSignOut = MutableSharedFlow<Unit>()
    val onSignOut = _onSignOut.asSharedFlow()

    private val _showPdfPasswordDialog = MutableStateFlow(false)
    val showPdfPasswordDialog: StateFlow<Boolean> = _showPdfPasswordDialog.asStateFlow()

    private val _pdfPassword = MutableStateFlow("")
    val pdfPassword: StateFlow<String> = _pdfPassword.asStateFlow()

    private val _pdfImportPreview = MutableStateFlow<PdfStockImportPreview?>(null)
    val pdfImportPreview: StateFlow<PdfStockImportPreview?> = _pdfImportPreview.asStateFlow()

    val fetchInterval: StateFlow<Int> = settingsDataStore.fetchIntervalFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 5)

    val lastStockListUpdateTime: StateFlow<Long?> = settingsDataStore.lastStockListUpdateTimeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val feeDiscount: StateFlow<Double> = settingsDataStore.feeDiscountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.28)

    val minFeeRegular: StateFlow<Int> = settingsDataStore.minFeeRegularFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1)

    val minFeeOddLot: StateFlow<Int> = settingsDataStore.minFeeOddLotFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1)

    val dividendFee: StateFlow<Int> = settingsDataStore.dividendFeeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 10)

    val preDeductSellFees: StateFlow<Boolean> = settingsDataStore.preDeductSellFeesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    val theme: StateFlow<String> = settingsDataStore.themeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "System")

    val stockDataSource: StateFlow<String> = settingsDataStore.stockDataSourceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "TWSE")

    val usStockDataSource: StateFlow<String> = settingsDataStore.usStockDataSourceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "Nasdaq")

    val notifyFallbackRepeatedly: StateFlow<Boolean> = settingsDataStore.notifyFallbackRepeatedlyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val skipPdfImportTutorial: StateFlow<Boolean> = settingsDataStore.skipPdfImportTutorialFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val taxRateNormalListedStock: StateFlow<Double> = settingsDataStore.taxRateNormalListedStockFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.003)

    val taxRateDomesticStockEtf: StateFlow<Double> = settingsDataStore.taxRateDomesticStockEtfFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.001)

    val taxRateBondEtf: StateFlow<Double> = settingsDataStore.taxRateBondEtfFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.0)

    val taxRateDayTrading: StateFlow<Double> = settingsDataStore.taxRateDayTradingFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.0015)

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

    fun onPdfImportRequest(uri: Uri) {
        pdfImportUri = uri
        _pdfPassword.value = ""
        _showPdfPasswordDialog.value = true
    }

    fun updatePdfPassword(password: String) {
        _pdfPassword.value = password
    }

    fun onPdfPasswordDialogDismiss() {
        _showPdfPasswordDialog.value = false
        _pdfPassword.value = ""
        pdfImportUri = null
    }

    fun parsePdfImport() {
        val uri = pdfImportUri ?: run {
            _message.value = "尚未選擇 PDF 檔案"
            return
        }

        val password = _pdfPassword.value
        if (password.isBlank()) {
            _message.value = "請先輸入 PDF 密碼"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val pdfBytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalArgumentException("無法讀取 PDF 檔案")

                val extraction = withContext(Dispatchers.Default) {
                    pdfHoldingImportService.extract(pdfBytes, password)
                }
                val preview = buildPdfImportPreview(extraction)

                _pdfImportPreview.value = preview
                _showPdfPasswordDialog.value = false
                _pdfPassword.value = ""
                pdfImportUri = null
            } catch (e: Exception) {
                val errorMessage = e.message.orEmpty()
                if (errorMessage.contains("the password is incorrect", ignoreCase = true)) {
                    _message.value = "PDF 密碼錯誤，請重新輸入。"
                } else {
                    Log.e(TAG, "PDF import parse failed", e)
                    _message.value = "PDF 解析錯誤"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun dismissPdfImportPreview() {
        _pdfImportPreview.value = null
    }

    fun importPdfPortfolio(replaceExisting: Boolean) {
        val preview = _pdfImportPreview.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val importableItems = preview.items.filter { it.currentPrice != null }
                if (importableItems.isEmpty()) {
                    throw IllegalArgumentException("沒有可匯入的股票，請確認目前價格是否抓取成功")
                }

                if (replaceExisting) {
                    deleteAllData()
                }

                val allStocksByCode = stockDao.getAllStocks().first().associateBy { it.code }
                val importDate = System.currentTimeMillis()

                importableItems.forEachIndexed { index, item ->
                    val existingStock = allStocksByCode[item.stockCode]
                    val stock = existingStock ?: Stock(
                        name = item.stockName.ifBlank { item.stockCode },
                        code = item.stockCode,
                        market = StockMarket.inferFromCode(item.stockCode)
                    ).also { stockDao.insertStock(it) }

                    val currentPrice = item.currentPrice ?: return@forEachIndexed
                    val expense = ((currentPrice * item.balance).roundToInt()).toDouble()

                    stockDao.insertTransaction(
                        StockTransaction(
                            stockCode = stock.code,
                            date = importDate,
                            recordTime = importDate + index,
                            type = "買進",
                            buyPrice = currentPrice,
                            buyShares = item.balance.toDouble(),
                            fee = 0.0,
                            tax = 0.0,
                            income = 0.0,
                            expense = expense,
                            note = "PDF 匯入快照"
                        )
                    )

                    if (existingStock == null) {
                        realtimeStockDataService.refreshStock(stock.code)
                    }
                }

                realtimeStockDataService.startFetching()
                val skippedCount = preview.items.size - importableItems.size
                _message.value = buildString {
                    append("PDF 匯入完成，共新增 ")
                    append(importableItems.size)
                    append(" 筆庫存快照")
                    if (skippedCount > 0) {
                        append("，略過 ")
                        append(skippedCount)
                        append(" 筆查無現價的資料")
                    }
                }
                _pdfImportPreview.value = null
            } catch (e: Exception) {
                _message.value = "PDF 匯入失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
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

    private suspend fun buildPdfImportPreview(
        extraction: com.rsps1008.stockify.data.PdfHoldingExtractionResult
    ): PdfStockImportPreview {
        val allStocksByCode = stockDao.getAllStocks().first().associateBy { it.code }

        val items = extraction.holdings.map { holding ->
            val stock = allStocksByCode[holding.stockCode]
            val priceInfo = realtimeStockDataService.fetchCurrentStockInfo(holding.stockCode)
            val currentPrice = priceInfo?.currentPrice

            PdfStockImportPreviewItem(
                stockCode = holding.stockCode,
                stockName = stock?.name.orEmpty(),
                balance = holding.balance,
                currentPrice = currentPrice,
                marketValue = currentPrice?.let { (it * holding.balance).roundToInt().toDouble() }
            )
        }.sortedBy { it.stockCode }

        return PdfStockImportPreview(
            extractedTextLength = extraction.extractedText.length,
            items = items
        )
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
            val shouldRefresh = stock == null
            if (stock == null) {
                val newStock = Stock(
                    name = csvTransaction.stockName,
                    code = csvTransaction.stockCode,
                    market = StockMarket.normalize(csvTransaction.market.ifBlank { StockMarket.inferFromCode(csvTransaction.stockCode) })
                )
                stockDao.insertStock(newStock)
            }
            stockDao.insertTransaction(csvTransaction.transaction)
            if (shouldRefresh) {
                realtimeStockDataService.refreshStock(csvTransaction.stockCode)
            }
        }
        realtimeStockDataService.startFetching()
        _message.value = "匯入成功，共 ${transactions.size} 筆紀錄"
    }

    fun setFetchInterval(interval: Int) {
        viewModelScope.launch {
            settingsDataStore.setFetchInterval(interval)
        }
    }

    fun setTheme(theme: String) {
        viewModelScope.launch {
            settingsDataStore.setTheme(theme)
        }
    }

    fun setStockDataSource(source: String) {
        viewModelScope.launch {
            settingsDataStore.setStockDataSource(source)
        }
    }

    fun setUsStockDataSource(source: String) {
        viewModelScope.launch {
            settingsDataStore.setUsStockDataSource(source)
        }
    }

    fun setNotifyFallbackRepeatedly(shouldNotifyRepeatedly: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setNotifyFallbackRepeatedly(shouldNotifyRepeatedly)
        }
    }

    fun clearRealtimeStockInfoCache() {
        viewModelScope.launch {
            settingsDataStore.clearRealtimeStockInfoCache()
            _message.value = "股價快取已清除"
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

    fun setDividendFee(fee: Int) {
        viewModelScope.launch {
            settingsDataStore.setDividendFee(fee)
        }
    }

    fun setPreDeductSellFees(preDeduct: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setPreDeductSellFees(preDeduct)
        }
    }

    fun setTaxRateNormalListedStock(rate: Double) {
        viewModelScope.launch {
            settingsDataStore.setTaxRateNormalListedStock(rate)
        }
    }

    fun setTaxRateDomesticStockEtf(rate: Double) {
        viewModelScope.launch {
            settingsDataStore.setTaxRateDomesticStockEtf(rate)
        }
    }

    fun setTaxRateBondEtf(rate: Double) {
        viewModelScope.launch {
            settingsDataStore.setTaxRateBondEtf(rate)
        }
    }

    fun setTaxRateDayTrading(rate: Double) {
        viewModelScope.launch {
            settingsDataStore.setTaxRateDayTrading(rate)
        }
    }

    fun setSkipPdfImportTutorial(skip: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setSkipPdfImportTutorial(skip)
        }
    }

    fun deleteAllDataAndShowToast() {
        viewModelScope.launch {
            deleteAllData()
            _message.value = "所有交易紀錄已刪除"
        }
    }

    private suspend fun deleteAllData() {
        stockDao.deleteAllTransactions()
    }

    fun updateStockListFromTwse() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val stocks = stockDataFetcher.fetchStockList()
                // Save to json file
                stockListRepository.saveStocks(stocks)
                // And also save to Room database
                stockDao.deleteStocksByMarket(StockMarket.TW)
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
