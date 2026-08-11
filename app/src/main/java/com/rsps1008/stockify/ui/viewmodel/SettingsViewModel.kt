package com.rsps1008.stockify.ui.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import android.content.ContentUris
import android.util.Log
import androidx.room.withTransaction
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.rsps1008.stockify.data.CsvService
import com.rsps1008.stockify.data.CsvTransaction
import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.StockifyApplication
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.rsps1008.stockify.data.GoogleDriveService
import com.rsps1008.stockify.data.HoldingsOrderBackupService
import com.rsps1008.stockify.data.ReturnRateMode
import com.rsps1008.stockify.data.PdfHoldingImportService
import com.rsps1008.stockify.data.PdfStockImportPreview
import com.rsps1008.stockify.data.PdfStockImportPreviewItem
import com.rsps1008.stockify.data.TextSizeMode
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockDataFetcher
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.StockListRepository
import com.rsps1008.stockify.data.TwseStockHistoryService
import com.rsps1008.stockify.data.assignProvisionalImportIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class DownloadBackupFile(
    val uri: Uri,
    val displayName: String,
    val modifiedAt: Long
)

internal fun accountsForReplacementRestore(restoredAccounts: List<Account>): List<Account> {
    return restoredAccounts.ifEmpty { listOf(Account(id = 1, name = "預設帳戶")) }
}

class SettingsViewModel(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    application: Application,
    private val realtimeStockDataService: RealtimeStockDataService,
    private val twseStockHistoryService: TwseStockHistoryService? = null
) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "SettingsViewModel"
        var hasShownLocalCsvRestoreFeeHintThisProcess = false
    }

    private val stockDataFetcher = StockDataFetcher()
    private val stockListRepository = StockListRepository(application)
    private val csvService = CsvService()
    private val pdfHoldingImportService = PdfHoldingImportService()
    private val holdingsOrderBackupService = HoldingsOrderBackupService()
    private val appDatabase = (application as StockifyApplication).database

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _updatingStockListMarket = MutableStateFlow<String?>(null)
    val updatingStockListMarket: StateFlow<String?> = _updatingStockListMarket.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _showImportConfirmDialog = MutableStateFlow(false)
    val showImportConfirmDialog: StateFlow<Boolean> = _showImportConfirmDialog.asStateFlow()

    private val _showLocalCsvRestoreFeeHintDialog = MutableStateFlow(false)
    val showLocalCsvRestoreFeeHintDialog: StateFlow<Boolean> = _showLocalCsvRestoreFeeHintDialog.asStateFlow()

    private val _downloadBackupFiles = MutableStateFlow<List<DownloadBackupFile>>(emptyList())
    val downloadBackupFiles: StateFlow<List<DownloadBackupFile>> = _downloadBackupFiles.asStateFlow()

    private val _downloadBackupType = MutableStateFlow<String?>(null)
    val downloadBackupType: StateFlow<String?> = _downloadBackupType.asStateFlow()

    private var importUri: Uri? = null
    private var importData: ByteArray? = null
    private var pdfImportUri: Uri? = null
    private var accountsBackupData: ByteArray? = null
    private var holdingsOrderBackupData: ByteArray? = null

    private val _googleSignInAccount = MutableStateFlow<GoogleSignInAccount?>(null)
    val googleSignInAccount: StateFlow<GoogleSignInAccount?> = _googleSignInAccount.asStateFlow()

    private val _cloudDataBackupUpdatedAt = MutableStateFlow<Long?>(null)
    val cloudDataBackupUpdatedAt: StateFlow<Long?> = _cloudDataBackupUpdatedAt.asStateFlow()

    private val _cloudOrderBackupUpdatedAt = MutableStateFlow<Long?>(null)
    val cloudOrderBackupUpdatedAt: StateFlow<Long?> = _cloudOrderBackupUpdatedAt.asStateFlow()

    private val _onSignOut = MutableSharedFlow<Unit>()
    val onSignOut = _onSignOut.asSharedFlow()

    private val _showPdfPasswordDialog = MutableStateFlow(false)
    val showPdfPasswordDialog: StateFlow<Boolean> = _showPdfPasswordDialog.asStateFlow()

    private val _pdfPassword = MutableStateFlow("")
    val pdfPassword: StateFlow<String> = _pdfPassword.asStateFlow()

    private val _pdfImportPreview = MutableStateFlow<PdfStockImportPreview?>(null)
    val pdfImportPreview: StateFlow<PdfStockImportPreview?> = _pdfImportPreview.asStateFlow()

    val accounts: StateFlow<List<Account>> = stockDao.getAllAccountsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val activeAccountId: StateFlow<Int> = settingsDataStore.activeAccountIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0)

    val fetchInterval: StateFlow<Int> = settingsDataStore.fetchIntervalFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 5)

    val lastStockListUpdateTime: StateFlow<Long?> = settingsDataStore.lastStockListUpdateTimeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val lastUsStockListUpdateTime: StateFlow<Long?> = settingsDataStore.lastUsStockListUpdateTimeFlow
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

    val returnRateMode: StateFlow<ReturnRateMode> = settingsDataStore.returnRateModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), ReturnRateMode.REMAINING_POSITION)

    val useCumulativeReturnRate: StateFlow<Boolean> = settingsDataStore.useCumulativeReturnRateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val calculationRoundingMode: StateFlow<String> = settingsDataStore.calculationRoundingModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), com.rsps1008.stockify.data.CalculationRoundingMode.ROUND)

    val theme: StateFlow<String> = settingsDataStore.themeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "System")

    val textSizeMode: StateFlow<String> = settingsDataStore.textSizeModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), TextSizeMode.DEFAULT)

    val showTaiwanWeightedIndex: StateFlow<Boolean> = settingsDataStore.showTaiwanWeightedIndexFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    val showTaiwanPortfolioChart: StateFlow<Boolean> = settingsDataStore.showTaiwanPortfolioChartFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    val stockDataSource: StateFlow<String> = settingsDataStore.stockDataSourceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "TWSE")

    val usStockDataSource: StateFlow<String> = settingsDataStore.usStockDataSourceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "Nasdaq")

    val fallbackNoticeEnabled: StateFlow<Boolean> = settingsDataStore.fallbackNoticeEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val skipPdfImportTutorial: StateFlow<Boolean> = settingsDataStore.skipPdfImportTutorialFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val holdingsOrder: StateFlow<List<String>> = settingsDataStore.holdingsOrderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val taxRateNormalListedStock: StateFlow<Double> = settingsDataStore.taxRateNormalListedStockFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.003)

    val taxRateDomesticStockEtf: StateFlow<Double> = settingsDataStore.taxRateDomesticStockEtfFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.001)

    val taxRateBondEtf: StateFlow<Double> = settingsDataStore.taxRateBondEtfFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.0)

    val taxRateDayTrading: StateFlow<Double> = settingsDataStore.taxRateDayTradingFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.0015)

    val marginFeatureEnabled: StateFlow<Boolean> = settingsDataStore.marginFeatureEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val marginDayCount: StateFlow<Int> = settingsDataStore.marginDayCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 365)

    val defaultMarginAnnualRate: StateFlow<Double> = settingsDataStore.defaultMarginAnnualRateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 6.45)

    val defaultShortBorrowAnnualRate: StateFlow<Double> = settingsDataStore.defaultShortBorrowAnnualRateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 3.5)

    val appLockEnabled: StateFlow<Boolean> = settingsDataStore.appLockEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val appLockBiometricEnabled: StateFlow<Boolean> = settingsDataStore.appLockBiometricEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    fun setMarginFeatureEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsDataStore.setMarginFeatureEnabled(enabled)
    }

    fun setMarginDayCount(dayCount: Int) = viewModelScope.launch {
        settingsDataStore.setMarginDayCount(dayCount)
    }

    fun setDefaultMarginAnnualRate(rate: Double) = viewModelScope.launch {
        settingsDataStore.setDefaultMarginAnnualRate(rate)
    }

    fun setDefaultShortBorrowAnnualRate(rate: Double) = viewModelScope.launch {
        settingsDataStore.setDefaultShortBorrowAnnualRate(rate)
    }

    fun enableAppLock(pin: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val succeeded = runCatching { settingsDataStore.enableAppLock(pin) }.isSuccess
        if (succeeded) _message.value = "應用程式鎖定已啟用"
        onResult(succeeded)
    }

    fun disableAppLock(pin: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val succeeded = settingsDataStore.disableAppLock(pin)
        if (succeeded) _message.value = "應用程式鎖定已關閉"
        onResult(succeeded)
    }

    fun changeAppLockPin(currentPin: String, newPin: String, onResult: (Boolean) -> Unit) =
        viewModelScope.launch {
            val succeeded = settingsDataStore.changeAppLockPin(currentPin, newPin)
            if (succeeded) _message.value = "數字密碼已更新"
            onResult(succeeded)
        }

    fun setAppLockBiometricEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsDataStore.setAppLockBiometricEnabled(enabled)
    }

    init {
        val account = GoogleSignIn.getLastSignedInAccount(getApplication())
        // 在 init 和 handleSignInResult 中
        val driveScope = Scope(DriveScopes.DRIVE_APPDATA)

        viewModelScope.launch {
            // Show the last known holdings backup time immediately while Drive is queried.
            _cloudDataBackupUpdatedAt.value = settingsDataStore.cloudDataBackupUpdatedAtFlow.first()

            if (account != null && GoogleSignIn.hasPermissions(account, driveScope)) {
                _googleSignInAccount.value = account
                refreshCloudBackupTimes(account)
            } else {
                // 如果登入成功但沒權限，可以發出一個訊息提示使用者要勾選權限
                _googleSignInAccount.value = null
                if (account != null) _message.value = "請務必勾選 Google Drive 權限以進行備份"
            }
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
                refreshCloudBackupTimes(account)
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
        _cloudDataBackupUpdatedAt.value = null
        _cloudOrderBackupUpdatedAt.value = null
        _message.value = "Google 登出成功"
    }

    private fun refreshCloudBackupTimes(account: GoogleSignInAccount) {
        viewModelScope.launch {
            val driveService = GoogleDriveService(getApplication(), account)
            driveService
                .getBackupModifiedTime("stockify_backup.csv")
                .getOrNull()
                ?.let { updatedAt ->
                    _cloudDataBackupUpdatedAt.value = updatedAt
                    settingsDataStore.setCloudDataBackupUpdatedAt(updatedAt)
                }
            _cloudOrderBackupUpdatedAt.value = driveService
                .getBackupModifiedTime("stockify_holdings_order.json")
                .getOrNull()
        }
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

                    // Also backup accounts
                    val accountsList = stockDao.getAllAccountsFlow().first()
                    val accountsJson = Json.encodeToString(accountsList).toByteArray(Charsets.UTF_8)
                    driveService.uploadBackup("stockify_accounts.json", accountsJson).getOrThrow()

                    val order = settingsDataStore.holdingsOrderFlow.first()
                    val realizedOrder = settingsDataStore.realizedHoldingsOrderFlow.first()
                    val orderJson = withContext(Dispatchers.IO) {
                        holdingsOrderBackupService.exportToBytes(order, realizedOrder)
                    }
                    driveService.uploadBackup(
                        fileName = "stockify_holdings_order.json",
                        content = orderJson,
                        mimeType = "application/json"
                    ).getOrThrow()

                    refreshCloudBackupTimes(account)
                    _message.value = "Google 雲端備份成功"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _message.value = "Google 雲端備份失敗: ${e.message}"
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
                    val accountsJson = driveService.restoreBackup("stockify_accounts.json").getOrNull()
                    val orderJson = driveService.restoreBackup("stockify_holdings_order.json").getOrNull()

                    importData = csvContent
                    accountsBackupData = accountsJson
                    holdingsOrderBackupData = orderJson
                    _showImportConfirmDialog.value = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _message.value = "Google 雲端還原失敗: ${e.message}"
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
                    } ?: error("無法建立備份檔案")
                }
                _message.value = "本地備份成功"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "本地備份失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun exportTransactionsToDownloads() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val transactions = stockDao.getTransactionsWithStock().first()
                val content = withContext(Dispatchers.IO) {
                    ByteArrayOutputStream().use { output ->
                        csvService.export(transactions, output)
                        output.toByteArray()
                    }
                }
                val fileName = "stockify_backup_${backupTimestamp()}.csv"
                withContext(Dispatchers.IO) {
                    writeToDownloads(fileName, "text/csv", content)
                }
                _message.value = "本地備份成功，已儲存至 Download/Stockify/$fileName"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "本地備份失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun exportAccounts(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val accounts = stockDao.getAllAccountsFlow().first()
                val content = Json.encodeToString(accounts).toByteArray(Charsets.UTF_8)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(content)
                    } ?: error("無法建立帳戶備份檔案")
                }
                _message.value = "本地帳戶名稱備份成功"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "本地帳戶名稱備份失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun exportAccountsToDownloads() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val accounts = stockDao.getAllAccountsFlow().first()
                val content = Json.encodeToString(accounts).toByteArray(Charsets.UTF_8)
                val fileName = "stockify_accounts_${backupTimestamp()}.json"
                withContext(Dispatchers.IO) {
                    writeToDownloads(fileName, "application/json", content)
                }
                _message.value = "本地帳戶名稱備份成功，已儲存至 Download/Stockify/$fileName"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "本地帳戶名稱備份失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun importAccounts(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val content = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBytes()
                    }
                } ?: error("無法讀取檔案")
                val accounts = Json.decodeFromString<List<Account>>(content.toString(Charsets.UTF_8))
                accounts.forEach { stockDao.insertAccount(it) }
                _message.value = "本地帳戶名稱還原成功，共 ${accounts.size} 個帳戶"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "本地帳戶名稱還原失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun exportHoldingsOrder(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val order = settingsDataStore.holdingsOrderFlow.first()
                val realizedOrder = settingsDataStore.realizedHoldingsOrderFlow.first()
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        holdingsOrderBackupService.export(order, realizedOrder, it)
                    } ?: error("無法建立排序備份檔案")
                }
                _message.value = "持股排序本地備份成功"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "持股排序本地備份失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun exportHoldingsOrderToDownloads() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val order = settingsDataStore.holdingsOrderFlow.first()
                val realizedOrder = settingsDataStore.realizedHoldingsOrderFlow.first()
                val content = withContext(Dispatchers.IO) {
                    holdingsOrderBackupService.exportToBytes(order, realizedOrder)
                }
                val fileName = "stockify_holdings_order_${backupTimestamp()}.json"
                withContext(Dispatchers.IO) {
                    writeToDownloads(fileName, "application/json", content)
                }
                _message.value = "持股排序本地備份成功，已儲存至 Download/Stockify/$fileName"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "持股排序本地備份失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun backupTimestamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())

    private fun writeToDownloads(fileName: String, mimeType: String, content: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("Android 9 以下需要檔案選擇器才能儲存備份")
        }
        writeToDownloadsOnQ(fileName, mimeType, content)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloadsOnQ(fileName: String, mimeType: String, content: ByteArray) {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Stockify")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("無法建立下載資料夾備份檔案")

        try {
            resolver.openOutputStream(uri)?.use { it.write(content) }
                ?: error("無法寫入下載資料夾備份檔案")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        } catch (e: CancellationException) {
            resolver.delete(uri, null, null)
            throw e
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun importHoldingsOrder(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val backup = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        holdingsOrderBackupService.import(it)
                    }
                } ?: com.rsps1008.stockify.data.HoldingsOrderBackupData(emptyList(), emptyList())

                settingsDataStore.setHoldingsOrder(backup.order)
                settingsDataStore.setRealizedHoldingsOrder(backup.realizedOrder)
                _message.value = "持股排序本地還原成功，共 ${backup.order.size + backup.realizedOrder.size} 筆"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "持股排序本地還原失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun backupHoldingsOrderToGoogleDrive() {
        viewModelScope.launch {
            _googleSignInAccount.value?.let { account ->
                _isLoading.value = true
                try {
                    val order = settingsDataStore.holdingsOrderFlow.first()
                    val realizedOrder = settingsDataStore.realizedHoldingsOrderFlow.first()
                    val content = withContext(Dispatchers.IO) {
                        holdingsOrderBackupService.exportToBytes(order, realizedOrder)
                    }
                    val driveService = GoogleDriveService(getApplication(), account)
                    driveService.uploadBackup(
                        fileName = "stockify_holdings_order.json",
                        content = content,
                        mimeType = "application/json"
                    ).getOrThrow()
                    refreshCloudBackupTimes(account)
                    _message.value = "持股排序 Google 雲端備份成功"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _message.value = "持股排序 Google 雲端備份失敗: ${e.message}"
                } finally {
                    _isLoading.value = false
                }
            } ?: run {
                _message.value = "請先登入 Google 帳號"
            }
        }
    }

    fun restoreHoldingsOrderFromGoogleDrive() {
        viewModelScope.launch {
            _googleSignInAccount.value?.let { account ->
                _isLoading.value = true
                try {
                    val driveService = GoogleDriveService(getApplication(), account)
                    val content = driveService.restoreBackup("stockify_holdings_order.json").getOrThrow()
                    val backup = withContext(Dispatchers.IO) {
                        holdingsOrderBackupService.import(content)
                    }
                    settingsDataStore.setHoldingsOrder(backup.order)
                    settingsDataStore.setRealizedHoldingsOrder(backup.realizedOrder)
                    _message.value = "持股排序 Google 雲端還原成功，共 ${backup.order.size + backup.realizedOrder.size} 筆"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _message.value = "持股排序 Google 雲端還原失敗: ${e.message}"
                } finally {
                    _isLoading.value = false
                }
            } ?: run {
                _message.value = "請先登入 Google 帳號"
            }
        }
    }

    fun onImportRequest(uri: Uri) {
        importUri = uri
        viewModelScope.launch {
            if (hasShownLocalCsvRestoreFeeHintThisProcess) {
                _showImportConfirmDialog.value = true
            } else {
                _showLocalCsvRestoreFeeHintDialog.value = true
            }
        }
    }

    fun onLocalCsvRestoreFeeHintConfirm() {
        hasShownLocalCsvRestoreFeeHintThisProcess = true
        _showLocalCsvRestoreFeeHintDialog.value = false
        _showImportConfirmDialog.value = true
    }

    fun onLocalCsvRestoreFeeHintCancel() {
        _showLocalCsvRestoreFeeHintDialog.value = false
        importUri = null
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
        accountsBackupData = null
        holdingsOrderBackupData = null
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
            } catch (e: CancellationException) {
                throw e
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

                val importDate = System.currentTimeMillis()
                val newStockCodes = appDatabase.withTransaction {
                    if (replaceExisting) {
                        deleteAllData()
                    } else {
                        // PDF snapshots use account 1. A prior full-data deletion may have
                        // removed it, so restore the default account before writing snapshots.
                        stockDao.insertAccount(Account(id = 1, name = "預設帳戶"))
                    }

                    val newCodes = linkedSetOf<String>()
                    importableItems.forEachIndexed { index, item ->
                        val existingStock = stockDao.getStockByCode(item.stockCode)
                        val stock = existingStock ?: Stock(
                            name = item.stockName.ifBlank { item.stockCode },
                            code = item.stockCode,
                            market = StockMarket.inferFromCode(item.stockCode)
                        ).also {
                            stockDao.insertStock(it)
                            newCodes += it.code
                        }

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
                    }
                    newCodes
                }

                refreshImportedStocks(newStockCodes)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "PDF 匯入失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun showDownloadBackups(type: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _message.value = "此裝置沒有可用的檔案選擇器，Android 9 以下請安裝檔案管理 App 後再還原"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val prefix = when (type) {
                    "transactions" -> "stockify_backup_"
                    "accounts" -> "stockify_accounts_"
                    "order" -> "stockify_holdings_order_"
                    else -> error("未知的備份類型")
                }
                _downloadBackupFiles.value = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val projection = arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_MODIFIED
                    )
                    resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        projection,
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                        arrayOf("${Environment.DIRECTORY_DOWNLOADS}/Stockify/", "$prefix%"),
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                    )?.use { cursor ->
                        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                        buildList {
                            while (cursor.moveToNext()) {
                                add(DownloadBackupFile(
                                    uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idIndex)),
                                    displayName = cursor.getString(nameIndex),
                                    modifiedAt = cursor.getLong(modifiedIndex) * 1000L
                                ))
                            }
                        }
                    }.orEmpty()
                }
                _downloadBackupType.value = type
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "讀取 Download/Stockify 備份失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun dismissDownloadBackups() {
        _downloadBackupType.value = null
        _downloadBackupFiles.value = emptyList()
    }

    fun restoreDownloadBackup(file: DownloadBackupFile) {
        when (_downloadBackupType.value) {
            "transactions" -> onImportRequest(file.uri)
            "accounts" -> importAccounts(file.uri)
            "order" -> importHoldingsOrder(file.uri)
        }
        dismissDownloadBackups()
    }

    private fun parseAccountsBackup(): List<Account> {
        val bytes = accountsBackupData ?: return emptyList()
        return try {
            Json.decodeFromString(bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw IllegalArgumentException("帳戶備份格式錯誤，已取消還原", e)
        }
    }

    private fun parseHoldingsOrderBackup(): com.rsps1008.stockify.data.HoldingsOrderBackupData? {
        val bytes = holdingsOrderBackupData ?: return null
        return try {
            holdingsOrderBackupService.import(bytes)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("持股排序備份格式錯誤，已取消還原", e)
        }
    }

    private suspend fun applyHoldingsOrderBackup(
        backup: com.rsps1008.stockify.data.HoldingsOrderBackupData?
    ) {
        backup ?: return
        try {
            settingsDataStore.setHoldingsOrder(backup.order)
            settingsDataStore.setRealizedHoldingsOrder(backup.realizedOrder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Applying holdings order backup failed after database commit", e)
        }
    }

    private suspend fun refreshImportedStocks(stockCodes: Set<String>) {
        try {
            if (stockCodes.isNotEmpty()) {
                realtimeStockDataService.refreshStocks(stockCodes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Imported data committed, but quote refresh failed", e)
        }

        try {
            realtimeStockDataService.startFetching()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Imported data committed, but background quote refresh failed", e)
        }
    }

    private fun performImportFromUri(uri: Uri, deleteOldData: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val csvTransactions = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        csvService.import(it)
                    }
                } ?: emptyList()
                importCsvTransactions(csvTransactions, deleteOldData)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "還原失敗: ${e.message}"
            } finally {
                _isLoading.value = false
                importUri = null
                accountsBackupData = null
                holdingsOrderBackupData = null
            }
        }
    }

    private suspend fun buildPdfImportPreview(
        extraction: com.rsps1008.stockify.data.PdfHoldingExtractionResult
    ): PdfStockImportPreview {
        val requestedStockCodes = extraction.holdings.map { it.stockCode }.distinct()
        val allStocksByCode = stockDao.getStocksByCodes(requestedStockCodes).associateBy { it.code }
        val priceRequestLimit = Semaphore(3)

        val items = coroutineScope {
            extraction.holdings.map { holding ->
                async(Dispatchers.IO) {
                    val stock = allStocksByCode[holding.stockCode]
                    val currentPrice = priceRequestLimit.withPermit {
                        realtimeStockDataService.fetchCurrentStockInfo(holding.stockCode)?.currentPrice
                    }

                    PdfStockImportPreviewItem(
                        stockCode = holding.stockCode,
                        stockName = stock?.name.orEmpty(),
                        balance = holding.balance,
                        currentPrice = currentPrice,
                        marketValue = currentPrice?.let { (it * holding.balance).roundToInt().toDouble() }
                    )
                }
            }.awaitAll().sortedBy { it.stockCode }
        }

        return PdfStockImportPreview(
            extractedTextLength = extraction.extractedText.length,
            items = items
        )
    }

    private fun performImportFromByteArray(data: ByteArray, deleteOldData: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val csvTransactions = withContext(Dispatchers.IO) {
                    ByteArrayInputStream(data).use { 
                        csvService.import(it)
                    }
                }
                importCsvTransactions(csvTransactions, deleteOldData)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "還原失敗: ${e.message}"
            } finally {
                _isLoading.value = false
                importData = null
                accountsBackupData = null
                holdingsOrderBackupData = null
            }
        }
    }

    private suspend fun importCsvTransactions(
        transactions: List<CsvTransaction>,
        deleteOldData: Boolean
    ) {
        require(transactions.isNotEmpty()) { "CSV 不含可還原的交易資料" }

        validateImportedTransactions(
            transactions,
            includeExistingTransactions = !deleteOldData
        )

        // Parse optional companion backups before any destructive database work.
        val restoredAccounts = parseAccountsBackup()
        val restoredOrder = parseHoldingsOrderBackup()
        val refreshedStockCodes = appDatabase.withTransaction {
            if (deleteOldData) {
                deleteAllData(restoredAccounts)
            } else if (restoredAccounts.isNotEmpty()) {
                restoredAccounts.forEach { stockDao.insertAccount(it) }
            }

            writeImportedTransactions(transactions)
        }

        applyHoldingsOrderBackup(restoredOrder)
        refreshImportedStocks(refreshedStockCodes)
        _message.value = "還原成功，共 ${transactions.size} 筆紀錄"
    }

    private suspend fun validateImportedTransactions(
        transactions: List<CsvTransaction>,
        includeExistingTransactions: Boolean
    ) {
        val existingTransactions = if (includeExistingTransactions) {
            stockDao.getAllTransactions().first()
        } else {
            emptyList()
        }
        val validationTransactions = assignProvisionalImportIds(
            existingTransactions = existingTransactions,
            importedTransactions = transactions.map { it.transaction }
        )
        val validationRows = transactions.zip(validationTransactions) { csvTransaction, transaction ->
            csvTransaction.copy(transaction = transaction)
        }
        com.rsps1008.stockify.data.FinancingTransactionValidationSupport
            .validate(existingTransactions + validationTransactions)
            ?.let { error -> throw Exception("$error，匯入被拒絕。") }

        val allStockCodes = validationRows.map { it.stockCode }.distinct()
        for (code in allStockCodes) {
            val importedForCode = validationRows.filter { it.stockCode == code }
            // Keep CSV restore consistent with transaction entry: the ticker is the
            // source of truth, so an old master record or a stale CSV market value
            // cannot turn a Taiwan security into US (or vice versa).
            val effectiveMarket = StockMarket.inferFromCode(code)
            importedForCode.forEach { csvTransaction ->
                com.rsps1008.stockify.data.FinancingTransactionValidationSupport
                    .validateFinancingMarket(csvTransaction.transaction, effectiveMarket)
                    ?.let { error -> throw Exception("股票 $code 的$error，匯入被拒絕。") }
            }

            val existingTxs = existingTransactions.filter { it.stockCode == code }
            val newTxs = importedForCode.map { it.transaction }
            val mergedTxs = existingTxs + newTxs
            val accountIds = mergedTxs.map { it.accountId }.distinct()

            for (accountId in accountIds) {
                val accountTxs = mergedTxs.filter { it.accountId == accountId }
                com.rsps1008.stockify.data.FinancingTransactionValidationSupport.validate(accountTxs)?.let { error ->
                    throw Exception("股票 $code 的$error，匯入被拒絕。")
                }
                if (!com.rsps1008.stockify.data.MarginCalculationSupport.hasValidRepaymentBalances(accountTxs)) {
                    throw Exception("股票 $code 包含超過剩餘本金的融資還款，匯入被拒絕。")
                }
                if (!com.rsps1008.stockify.data.ShortSellingCalculationSupport.hasValidCoverBalances(accountTxs)) {
                    throw Exception("股票 $code 包含超過剩餘股數或無效批次的融券操作，匯入被拒絕。")
                }
            }
        }
    }

    private suspend fun writeImportedTransactions(transactions: List<CsvTransaction>): Set<String> {
        val refreshedStockCodes = linkedSetOf<String>()

        transactions.forEach { csvTransaction ->
            val stock = stockDao.getStockByCode(csvTransaction.stockCode)
            val inferredMarket = StockMarket.inferFromCode(csvTransaction.stockCode)
            if (stock == null) {
                val newStock = Stock(
                    name = csvTransaction.stockName,
                    code = csvTransaction.stockCode,
                    market = inferredMarket
                )
                stockDao.insertStock(newStock)
            } else if (StockMarket.normalize(stock.market) != inferredMarket) {
                stockDao.updateStock(stock.copy(market = inferredMarket))
            }

            val accountId = csvTransaction.transaction.accountId
            val existingAccount = stockDao.getAccountById(accountId)
            if (existingAccount == null) {
                stockDao.insertAccount(Account(id = accountId, name = "未命名帳戶 ($accountId)"))
            }

            stockDao.insertTransaction(csvTransaction.transaction)
            refreshedStockCodes += csvTransaction.stockCode
        }
        return refreshedStockCodes
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

    fun setTextSizeMode(mode: String) {
        viewModelScope.launch {
            settingsDataStore.setTextSizeMode(mode)
        }
    }

    fun setShowTaiwanWeightedIndex(show: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setShowTaiwanWeightedIndex(show)
        }
    }

    fun setShowTaiwanPortfolioChart(show: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setShowTaiwanPortfolioChart(show)
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

    fun setFallbackNoticeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setFallbackNoticeEnabled(enabled)
        }
    }

    fun clearRealtimeStockInfoCache() {
        viewModelScope.launch {
            settingsDataStore.clearRealtimeStockInfoCache()
            _message.value = "股價快取已清除"
        }
    }

    fun clearHistoryPriceData() {
        viewModelScope.launch {
            stockDao.deleteAllHistoryPrices()
            twseStockHistoryService?.clearCache()
            _message.value = "圖表歷史價格資料已清除"
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

    fun setReturnRateMode(mode: ReturnRateMode) {
        viewModelScope.launch {
            settingsDataStore.setReturnRateMode(mode)
        }
    }

    fun setUseCumulativeReturnRate(useCumulative: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setUseCumulativeReturnRate(useCumulative)
        }
    }

    fun setCalculationRoundingMode(mode: String) {
        viewModelScope.launch {
            settingsDataStore.setCalculationRoundingMode(mode)
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
            stockDao.deleteAllTransactions()
            _message.value = "所有帳戶的交易紀錄已刪除，帳戶資料已保留"
        }
    }

    fun deleteAccountTransactionsAndShowToast(accountId: Int, accountName: String) {
        viewModelScope.launch {
            stockDao.deleteTransactionsByAccountId(accountId)
            _message.value = "帳戶「$accountName」的交易紀錄已刪除，帳戶資料已保留"
        }
    }

    fun deleteAllUserDataAndShowToast() {
        viewModelScope.launch {
            appDatabase.withTransaction {
                stockDao.deleteAllTransactions()
                stockDao.deleteAllAccounts()
            }
            settingsDataStore.setHoldingsOrder(emptyList())
            settingsDataStore.setRealizedHoldingsOrder(emptyList())
            settingsDataStore.setActiveAccountId(0)
            settingsDataStore.clearRealtimeStockInfoCache()
            twseStockHistoryService?.clearCache()
            _message.value = "所有持股、帳戶與排序資料已刪除"
        }
    }

    private suspend fun deleteAllData(restoredAccounts: List<Account> = emptyList()) {
        stockDao.deleteAllTransactions()
        stockDao.deleteAllAccounts()
        accountsForReplacementRestore(restoredAccounts).forEach { stockDao.insertAccount(it) }
    }

    fun updateStockListFromTwse() {
        viewModelScope.launch {
            _isLoading.value = true
            _updatingStockListMarket.value = StockMarket.TW
            try {
                val stocks = stockDataFetcher.fetchStockList()
                val updatedStocks = stockListRepository.replaceStocksInDatabase(
                    database = appDatabase,
                    market = StockMarket.TW,
                    stocks = stocks
                )
                try {
                    stockListRepository.saveStocks(updatedStocks)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Taiwan stock list database updated, but local cache save failed", e)
                }
                settingsDataStore.setLastStockListUpdateTime(System.currentTimeMillis())
                _message.value = "股票列表更新成功！共 ${updatedStocks.size} 筆"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "更新失敗: ${e.message}"
            } finally {
                _updatingStockListMarket.value = null
                _isLoading.value = false
            }
        }
    }

    fun updateStockListFromNasdaq() {
        viewModelScope.launch {
            _isLoading.value = true
            _updatingStockListMarket.value = StockMarket.US
            try {
                val stocks = stockDataFetcher.fetchUsStockList()
                if (stocks.isEmpty()) {
                    throw IllegalStateException("Nasdaq Trader 未回傳可用的美股資料")
                }
                val updatedStocks = stockListRepository.replaceStocksInDatabase(
                    database = appDatabase,
                    market = StockMarket.US,
                    stocks = stocks
                )
                settingsDataStore.setLastUsStockListUpdateTime(System.currentTimeMillis())
                _message.value = "美股股票列表更新成功！共 ${updatedStocks.size} 筆"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update US stock list from Nasdaq Trader", e)
                _message.value = "美股列表更新失敗: ${e.message}"
            } finally {
                _updatingStockListMarket.value = null
                _isLoading.value = false
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }
}
