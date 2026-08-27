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
import com.rsps1008.stockify.data.validatedRestoredAccounts
import com.rsps1008.stockify.data.resolvedActiveAccountId
import com.rsps1008.stockify.StockifyApplication
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.rsps1008.stockify.data.GoogleDriveService
import com.rsps1008.stockify.data.GoogleDriveBackupBundle
import com.rsps1008.stockify.data.HoldingsOrderBackupService
import com.rsps1008.stockify.data.ReturnRateMode
import com.rsps1008.stockify.data.PdfHoldingImportService
import com.rsps1008.stockify.data.PdfStockImportPreview
import com.rsps1008.stockify.data.PdfStockImportPreviewItem
import com.rsps1008.stockify.data.PdfStockImportSupport
import com.rsps1008.stockify.data.CsvTransactionDedupSupport
import com.rsps1008.stockify.data.TextSizeMode
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockDataFetcher
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.StockKey
import com.rsps1008.stockify.data.canonicalStockCode
import com.rsps1008.stockify.data.toStockKey
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.StockListRepository
import com.rsps1008.stockify.data.StockListSyncCoordinator
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

internal fun requiredExistingTransactionKeysForImport(
    importedStockKeys: List<StockKey>,
    existingStocksByCode: Map<String, List<Stock>>
): List<StockKey> = buildList {
    addAll(importedStockKeys)
    val marketAliases = CsvTransactionDedupSupport.marketRepairAliases(
        importedStockKeys = importedStockKeys,
        existingStocksByCode = existingStocksByCode
    )
    existingStocksByCode.values
        .asSequence()
        .flatten()
        .filter { marketAliases.containsKey(it.toStockKey().cacheKey()) }
        .map { it.toStockKey() }
        .forEach(::add)
}.distinctBy { it.cacheKey() }

internal fun accountsForReplacementRestore(restoredAccounts: List<Account>): List<Account> {
    return restoredAccounts.ifEmpty { listOf(Account(id = 1, name = "預設帳戶")) }
}

internal fun resolvePdfImportAccount(
    activeAccountId: Int,
    existingAccounts: List<Account>
): Account {
    return existingAccounts.firstOrNull { it.id == activeAccountId && it.id > 0 }
        ?: Account(id = 1, name = "預設帳戶")
}

internal class CsvImportValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal fun describeCsvImportRow(csvTransaction: CsvTransaction): String {
    val details = listOfNotNull(
        csvTransaction.sourceRowNumber?.let { "CSV 第 ${it} 列" },
        csvTransaction.sourceId.trim().takeIf(String::isNotBlank)?.let { "id=$it" },
        csvTransaction.stockCode.trim().takeIf(String::isNotBlank)?.let { "股號=$it" },
        csvTransaction.transaction.type.trim().takeIf(String::isNotBlank)?.let { "交易=$it" },
        "帳戶=${csvTransaction.transaction.accountId}"
    )
    return if (details.isEmpty()) "CSV 資料" else details.joinToString("，", postfix = "）", prefix = "（")
}

internal fun describeCsvImportRows(rows: Collection<CsvTransaction>): String {
    if (rows.isEmpty()) return "找不到對應的 CSV 列，可能是既有資料造成"
    val uniqueRows = rows.distinctBy { it.sourceRowNumber to it.sourceId }
    val displayed = uniqueRows.take(3)
    val displayedRows = displayed
        .joinToString("；", transform = ::describeCsvImportRow)
    val remainingCount = uniqueRows.size - displayed.size
    return if (remainingCount > 0) {
        "相關資料：$displayedRows；另有 $remainingCount 筆相關列"
    } else {
        "相關資料：$displayedRows"
    }
}

internal fun canonicalizeCsvTransaction(
    csvTransaction: CsvTransaction,
    validateMarket: Boolean = true
): CsvTransaction {
    val stockCode = canonicalStockCode(csvTransaction.stockCode)
    val market = StockMarket.normalize(csvTransaction.market)
    if (validateMarket) {
        require(market == StockMarket.inferFromCode(stockCode)) {
            "市場 $market 與股號 $stockCode 推斷的 ${StockMarket.inferFromCode(stockCode)} 不一致"
        }
    }
    return csvTransaction.copy(
        stockCode = stockCode,
        market = market,
        transaction = csvTransaction.transaction.copy(
            stockCode = stockCode,
            market = market
        )
    )
}

private data class ExistingCsvImportData(
    val transactions: List<StockTransaction>,
    val stocksByCode: Map<String, List<Stock>>,
    val marketAliases: Map<String, String>
)

class SettingsViewModel(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    application: Application,
    private val realtimeStockDataService: RealtimeStockDataService,
    private val twseStockHistoryService: TwseStockHistoryService? = null
) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "SettingsViewModel"
        const val SQLITE_IN_CHUNK_SIZE = 500
        var hasShownLocalCsvRestoreFeeHintThisProcess = false
    }

    private val stockDataFetcher = StockDataFetcher(
        (application as StockifyApplication).httpClient
    )
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

    private val _showForceImportConfirmDialog = MutableStateFlow(false)
    val showForceImportConfirmDialog: StateFlow<Boolean> = _showForceImportConfirmDialog.asStateFlow()

    private val _forceImportReason = MutableStateFlow<String?>(null)
    val forceImportReason: StateFlow<String?> = _forceImportReason.asStateFlow()

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
    private var pendingForceImportTransactions: List<CsvTransaction>? = null
    private var pendingForceImportDeleteOldData = false

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
            val bundleUpdatedAt = driveService.getBackupModifiedTime(GoogleDriveBackupBundle.FILE_NAME).getOrNull()
            val dataUpdatedAt = bundleUpdatedAt
                ?: driveService.getBackupModifiedTime("stockify_backup.csv").getOrNull()
            dataUpdatedAt
                ?.let { updatedAt ->
                    _cloudDataBackupUpdatedAt.value = updatedAt
                    settingsDataStore.setCloudDataBackupUpdatedAt(updatedAt)
                }
            val legacyOrderUpdatedAt = driveService
                .getBackupModifiedTime("stockify_holdings_order.json")
                .getOrNull()
            _cloudOrderBackupUpdatedAt.value = listOfNotNull(
                bundleUpdatedAt,
                legacyOrderUpdatedAt
            ).maxOrNull()
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
                    val accountsList = stockDao.getAllAccountsFlow().first()
                    val accountsJson = Json.encodeToString(accountsList).toByteArray(Charsets.UTF_8)
                    val order = settingsDataStore.holdingsOrderFlow.first()
                    val realizedOrder = settingsDataStore.realizedHoldingsOrderFlow.first()
                    val orderJson = withContext(Dispatchers.IO) {
                        holdingsOrderBackupService.exportToBytes(order, realizedOrder)
                    }
                    val bundle = withContext(Dispatchers.Default) {
                        GoogleDriveBackupBundle.create(csvContent, accountsJson, orderJson)
                    }
                    val driveService = GoogleDriveService(getApplication(), account)
                    driveService.uploadBackup(
                        fileName = GoogleDriveBackupBundle.FILE_NAME,
                        content = bundle,
                        mimeType = "application/zip"
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
                    val bundleFile = driveService
                        .restoreBackupWithModifiedTimeIfPresent(GoogleDriveBackupBundle.FILE_NAME)
                        .getOrThrow()
                    val restored = bundleFile?.content?.let {
                        withContext(Dispatchers.Default) { GoogleDriveBackupBundle.restore(it) }
                    }
                    val legacyOrderFile = driveService
                        .restoreBackupWithModifiedTimeIfPresent("stockify_holdings_order.json")
                        .getOrNull()
                    val csvContent = restored?.transactionsCsv
                        ?: driveService.restoreBackup("stockify_backup.csv").getOrThrow()
                    val accountsJson = restored?.accountsJson
                        ?: driveService.restoreBackup("stockify_accounts.json").getOrNull()
                    val orderJson = com.rsps1008.stockify.data.GoogleDriveBackupSelectionSupport
                        .selectHoldingsOrder(
                            bundleFile = bundleFile,
                            restoredBundle = restored,
                            legacyOrderFile = legacyOrderFile
                        )

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
                val accounts = validatedRestoredAccounts(
                    Json.decodeFromString<List<Account>>(content.toString(Charsets.UTF_8))
                )
                appDatabase.withTransaction {
                    stockDao.replaceAccounts(accounts)
                }
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
        clearImportBuffers()
        clearForceImportState()
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
        clearImportBuffers()
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
        clearForceImportState()
        clearImportBuffers()
    }

    fun onForceImportConfirm() {
        val transactions = pendingForceImportTransactions ?: return
        val deleteOldData = pendingForceImportDeleteOldData
        clearForceImportState()
        _showForceImportConfirmDialog.value = false
        performForceImport(transactions, deleteOldData)
    }

    fun onForceImportCancel() {
        _showForceImportConfirmDialog.value = false
        clearForceImportState()
        clearImportBuffers()
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
                val importableItems = PdfStockImportSupport.itemsReadyForImport(preview.items)
                    .map { item -> item.copy(stockCode = canonicalStockCode(item.stockCode)) }
                if (importableItems.isEmpty()) {
                    throw IllegalArgumentException("沒有可匯入的股票，請確認庫存股數與目前價格是否有效")
                }

                val importDate = System.currentTimeMillis()
                val stockKeysToRefresh = PdfStockImportSupport.stockKeysToRefresh(importableItems)
                val activeAccountIdBeforeImport = settingsDataStore.activeAccountIdFlow.first()
                val existingAccounts = stockDao.getAllAccountsFlow().first()
                val importAccount = resolvePdfImportAccount(
                    activeAccountId = activeAccountIdBeforeImport,
                    existingAccounts = existingAccounts
                )
                appDatabase.withTransaction {
                    if (replaceExisting) {
                        deleteAllData(listOf(importAccount))
                    } else {
                        stockDao.insertAccount(importAccount)
                    }

                    repairImportedStockIdentities(
                        importableItems.map { item ->
                            StockKey(StockMarket.inferFromCode(item.stockCode), item.stockCode)
                        }
                    )

                    importableItems.forEachIndexed { index, item ->
                        val market = StockMarket.inferFromCode(item.stockCode)
                        val existingStock = stockDao.getStockByCode(item.stockCode, market)
                        val stock = existingStock ?: Stock(
                            name = item.stockName.ifBlank { item.stockCode },
                            code = item.stockCode,
                            market = market
                        )

                        val currentPrice = item.currentPrice ?: return@forEachIndexed
                        val expense = ((currentPrice * item.balance).roundToInt()).toDouble()
                        val snapshot = StockTransaction(
                            stockCode = stock.code,
                            market = stock.market,
                            accountId = importAccount.id,
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
                        com.rsps1008.stockify.data.TransactionValidationSupport
                            .validateForWrite(snapshot)
                            ?.let { error -> throw IllegalArgumentException("PDF 快照無效：$error") }

                        if (existingStock == null) {
                            stockDao.insertStock(stock)
                        }
                        stockDao.insertTransaction(snapshot)
                    }
                }

                if (
                    activeAccountIdBeforeImport > 0 &&
                    activeAccountIdBeforeImport != importAccount.id
                ) {
                    settingsDataStore.setActiveAccountId(importAccount.id)
                }

                refreshImportedStocks(stockKeysToRefresh)
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

    private suspend fun refreshImportedStocks(stockKeys: Set<StockKey>) {
        try {
            if (stockKeys.isNotEmpty()) {
                realtimeStockDataService.refreshStocks(stockKeys)
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

    private fun handleImportFailure(
        error: Exception,
        parsedTransactions: List<CsvTransaction>?,
        deleteOldData: Boolean
    ) {
        val detail = error.message
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: "系統沒有提供詳細原因，請確認備份檔案仍可讀取"
        _message.value = "匯入失敗：$detail"

        if (error is CsvImportValidationException && !parsedTransactions.isNullOrEmpty()) {
            pendingForceImportTransactions = parsedTransactions
            pendingForceImportDeleteOldData = deleteOldData
            _forceImportReason.value = detail
            _showForceImportConfirmDialog.value = true
        } else {
            clearForceImportState()
            clearImportBuffers()
        }
    }

    private fun performForceImport(
        transactions: List<CsvTransaction>,
        deleteOldData: Boolean
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                importCsvTransactions(
                    transactions = transactions,
                    deleteOldData = deleteOldData,
                    forceImport = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = e.message
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: "系統沒有提供詳細原因，請確認備份檔案仍可讀取"
                _message.value = "強制匯入失敗：$detail"
            } finally {
                _isLoading.value = false
                clearForceImportState()
                clearImportBuffers()
            }
        }
    }

    private fun clearImportBuffers() {
        importUri = null
        importData = null
        accountsBackupData = null
        holdingsOrderBackupData = null
    }

    private fun clearForceImportState() {
        pendingForceImportTransactions = null
        pendingForceImportDeleteOldData = false
        _showForceImportConfirmDialog.value = false
        _forceImportReason.value = null
    }

    private fun performImportFromUri(uri: Uri, deleteOldData: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            var csvTransactions: List<CsvTransaction>? = null
            try {
                val parsedTransactions = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        csvService.import(it, validateMarket = false)
                    }
                } ?: emptyList()
                csvTransactions = parsedTransactions
                importCsvTransactions(parsedTransactions, deleteOldData)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleImportFailure(
                    error = e,
                    parsedTransactions = csvTransactions,
                    deleteOldData = deleteOldData
                )
            } finally {
                _isLoading.value = false
                if (pendingForceImportTransactions == null) {
                    clearImportBuffers()
                }
            }
        }
    }

    private suspend fun buildPdfImportPreview(
        extraction: com.rsps1008.stockify.data.PdfHoldingExtractionResult
    ): PdfStockImportPreview {
        val requestedStockKeys = extraction.holdings
            .map {
                val stockCode = canonicalStockCode(it.stockCode)
                StockKey(StockMarket.inferFromCode(stockCode), stockCode)
            }
            .distinctBy { it.cacheKey() }
        val allStocksByKey = requestedStockKeys
            .groupBy { StockMarket.normalize(it.market) }
            .flatMap { (market, keys) ->
                stockDao.getStocksByMarketAndCodes(market, keys.map { it.code })
            }
            .associateBy { it.toStockKey().cacheKey() }
        val priceRequestLimit = Semaphore(3)

        val items = coroutineScope {
            extraction.holdings.map { holding ->
                async(Dispatchers.IO) {
                    val stockCode = canonicalStockCode(holding.stockCode)
                    val market = StockMarket.inferFromCode(stockCode)
                    val stock = allStocksByKey[StockKey(market, stockCode).cacheKey()]
                    val currentPrice = priceRequestLimit.withPermit {
                        realtimeStockDataService.fetchCurrentStockInfo(
                            stockCode = stockCode,
                            market = market,
                            forceRefresh = true
                        )?.currentPrice
                    }

                    PdfStockImportPreviewItem(
                        stockCode = stockCode,
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
            var csvTransactions: List<CsvTransaction>? = null
            try {
                val parsedTransactions = withContext(Dispatchers.IO) {
                    ByteArrayInputStream(data).use { 
                        csvService.import(it, validateMarket = false)
                    }
                }
                csvTransactions = parsedTransactions
                importCsvTransactions(parsedTransactions, deleteOldData)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleImportFailure(
                    error = e,
                    parsedTransactions = csvTransactions,
                    deleteOldData = deleteOldData
                )
            } finally {
                _isLoading.value = false
                if (pendingForceImportTransactions == null) {
                    clearImportBuffers()
                }
            }
        }
    }

    private suspend fun importCsvTransactions(
        transactions: List<CsvTransaction>,
        deleteOldData: Boolean,
        forceImport: Boolean = false
    ) {
        val canonicalTransactions = transactions.map { csvTransaction ->
            try {
                canonicalizeCsvTransaction(csvTransaction, validateMarket = !forceImport)
            } catch (e: Exception) {
                throw CsvImportValidationException(
                    "${describeCsvImportRow(csvTransaction)}：${e.message ?: "市場或股號資料無效"}",
                    e
                )
            }
        }
        require(canonicalTransactions.isNotEmpty()) { "CSV 不含可還原的交易資料，請確認選取的是交易備份 CSV" }
        val existingImportData = if (deleteOldData) {
            null
        } else {
            loadExistingCsvImportData(canonicalTransactions)
        }
        val transactionsToImport = if (deleteOldData) {
            canonicalTransactions
        } else {
            CsvTransactionDedupSupport.filterNewTransactions(
                importedTransactions = canonicalTransactions,
                existingTransactions = existingImportData?.transactions.orEmpty(),
                marketAliases = existingImportData?.marketAliases.orEmpty()
            )
        }

        if (!forceImport) {
            validateImportedTransactions(
                transactionsToImport,
                includeExistingTransactions = !deleteOldData,
                existingImportData = existingImportData
            )
        }

        // Parse optional companion backups before any destructive database work.
        // In force mode, preserve the transaction CSV even if an optional cloud
        // account/order backup is damaged.
        val companionWarnings = mutableListOf<String>()
        val restoredAccounts = try {
            val parsedAccounts = parseAccountsBackup()
            if (parsedAccounts.isEmpty()) emptyList() else validatedRestoredAccounts(parsedAccounts)
        } catch (e: Exception) {
            val detail = e.message?.trim().takeIf { !it.isNullOrBlank() } ?: "格式無法辨識"
            if (!forceImport) {
                throw CsvImportValidationException(
                    "帳戶備份無法還原：$detail；${describeCsvImportRows(transactionsToImport)}；匯入被拒絕。",
                    e
                )
            }
            companionWarnings += "帳戶備份未還原（$detail）"
            emptyList()
        }
        val restoredOrder = try {
            parseHoldingsOrderBackup()
        } catch (e: Exception) {
            val detail = e.message?.trim().takeIf { !it.isNullOrBlank() } ?: "格式無法辨識"
            if (!forceImport) {
                throw CsvImportValidationException(
                    "持股排序備份無法還原：$detail；${describeCsvImportRows(transactionsToImport)}；匯入被拒絕。",
                    e
                )
            }
            companionWarnings += "持股排序備份未還原（$detail）"
            null
        }
        val refreshedStockCodes = appDatabase.withTransaction {
            if (deleteOldData) {
                deleteAllData(restoredAccounts)
            } else if (restoredAccounts.isNotEmpty()) {
                stockDao.replaceAccounts(restoredAccounts)
            }

            writeImportedTransactions(
                transactions = transactionsToImport,
                repairStockKeys = if (deleteOldData) {
                    emptyList()
                } else {
                    canonicalTransactions.map { StockKey(it.market, it.stockCode) }
                }
            )
        }

        applyHoldingsOrderBackup(restoredOrder)
        if (deleteOldData) {
            val activeAccountId = settingsDataStore.activeAccountIdFlow.first()
            settingsDataStore.setActiveAccountId(
                resolvedActiveAccountId(activeAccountId, accountsForReplacementRestore(restoredAccounts))
            )
        }
        refreshImportedStocks(refreshedStockCodes)
        _message.value = if (forceImport) {
            val companionWarning = companionWarnings.takeIf { it.isNotEmpty() }
                ?.joinToString("；", prefix = "另外，", postfix = "。")
                .orEmpty()
            "已強制匯入 ${transactionsToImport.size} 筆紀錄。請檢查持股、損益與報酬率，部分資料可能無法正確計算。$companionWarning"
        } else {
            "匯入成功，共 ${transactionsToImport.size} 筆紀錄"
        }
    }

    private suspend fun loadExistingCsvImportData(
        transactions: List<CsvTransaction>
    ): ExistingCsvImportData {
        val importedStockKeys = transactions
            .map { StockKey(it.market, it.stockCode) }
            .distinctBy { it.cacheKey() }
        val existingStocksByCode = importedStockKeys
            .map { it.normalizedCode }
            .distinct()
            .chunked(SQLITE_IN_CHUNK_SIZE)
            .flatMap { stockDao.getStocksByCodesForImportRepair(it) }
            .groupBy { canonicalStockCode(it.code) }
        val marketAliases = CsvTransactionDedupSupport.marketRepairAliases(
            importedStockKeys = importedStockKeys,
            existingStocksByCode = existingStocksByCode
        )
        val existingTransactions = requiredExistingTransactionKeysForImport(importedStockKeys, existingStocksByCode)
            .groupBy { it.normalizedMarket }
            .flatMap { (market, stockKeys) ->
                stockKeys
                    .map { it.normalizedCode }
                    .chunked(SQLITE_IN_CHUNK_SIZE)
                    .flatMap { stockDao.getTransactionsForStockCodesAndMarket(market, it) }
            }
        return ExistingCsvImportData(
            transactions = CsvTransactionDedupSupport.canonicalizeExistingTransactions(
                existingTransactions = existingTransactions,
                marketAliases = marketAliases
            ),
            stocksByCode = existingStocksByCode,
            marketAliases = marketAliases
        )
    }

    private suspend fun validateImportedTransactions(
        transactions: List<CsvTransaction>,
        includeExistingTransactions: Boolean,
        existingImportData: ExistingCsvImportData? = null
    ) {
        if (transactions.isEmpty()) return
        val allStockKeys = transactions
            .map { StockKey(it.market, it.stockCode) }
            .distinctBy { it.cacheKey() }
        val importedCodes = allStockKeys.map { it.normalizedCode }.distinct()
        val existingStocksByCode = if (includeExistingTransactions) {
            existingImportData?.stocksByCode ?: importedCodes
                .chunked(SQLITE_IN_CHUNK_SIZE)
                .flatMap { stockDao.getStocksByCodesForImportRepair(it) }
                .groupBy { canonicalStockCode(it.code) }
        } else {
            emptyMap()
        }
        val existingTransactions = if (includeExistingTransactions) {
            existingImportData?.transactions ?: requiredExistingTransactionKeysForImport(allStockKeys, existingStocksByCode)
                .groupBy { it.normalizedMarket }
                .flatMap { (market, stockKeys) ->
                    stockKeys
                        .map { it.normalizedCode }
                        .chunked(SQLITE_IN_CHUNK_SIZE)
                        .flatMap { stockDao.getTransactionsForStockCodesAndMarket(market, it) }
                }
        } else {
            emptyList()
        }
        val validationTransactions = assignProvisionalImportIds(
            maxExistingId = if (includeExistingTransactions) stockDao.getMaxTransactionId() else 0,
            importedTransactions = transactions.map { it.transaction }
        )
        val validationRows = transactions.zip(validationTransactions) { csvTransaction, transaction ->
            csvTransaction.copy(transaction = transaction)
        }
        validationRows.forEach { csvTransaction ->
            com.rsps1008.stockify.data.TransactionValidationSupport
                .validateForWrite(csvTransaction.transaction)
                ?.let { error ->
                    throw CsvImportValidationException(
                        "${describeCsvImportRow(csvTransaction)}：$error，匯入被拒絕。"
                    )
                }
        }
        com.rsps1008.stockify.data.FinancingTransactionValidationSupport
            .validate(existingTransactions + validationTransactions)
            ?.let { error ->
                throw CsvImportValidationException(
                    "$error；${describeCsvImportRows(validationRows)}；匯入被拒絕。"
                )
            }

        val importedRowsByStockKey = validationRows.groupBy {
            StockKey(it.market, it.stockCode).cacheKey()
        }
        val existingTransactionsByCode = existingTransactions.groupBy {
            canonicalStockCode(it.stockCode)
        }
        for (stockKey in allStockKeys) {
            val code = stockKey.code
            val importedForCode = importedRowsByStockKey[stockKey.cacheKey()].orEmpty()
            // Keep CSV restore consistent with transaction entry: the ticker is the
            // source of truth, so an old master record or a stale CSV market value
            // cannot turn a Taiwan security into US (or vice versa).
            val effectiveMarket = stockKey.normalizedMarket
            importedForCode.forEach { csvTransaction ->
                com.rsps1008.stockify.data.FinancingTransactionValidationSupport
                    .validateFinancingMarket(csvTransaction.transaction, effectiveMarket)
                    ?.let { error ->
                        throw CsvImportValidationException(
                            "${describeCsvImportRow(csvTransaction)}：股票 $code 的$error，匯入被拒絕。"
                        )
                    }
            }

            val existingTxs = existingTransactionsByCode[canonicalStockCode(code)].orEmpty()
                .filter { StockMarket.normalize(it.market) == stockKey.normalizedMarket }
            val newTxs = importedForCode.map { it.transaction }
            val mergedTxs = existingTxs + newTxs
            val accountIds = mergedTxs.map { it.accountId }.distinct()

            for (accountId in accountIds) {
                val accountTxs = mergedTxs.filter { it.accountId == accountId }
                com.rsps1008.stockify.data.FinancingTransactionValidationSupport.validate(accountTxs)?.let { error ->
                    throw CsvImportValidationException(
                        "股票 $code、帳戶 $accountId 的$error；${describeCsvImportRows(
                            importedForCode.filter { it.transaction.accountId == accountId }
                        )}；匯入被拒絕。"
                    )
                }
                com.rsps1008.stockify.data.HoldingCalculationSupport
                    .validateLongPositionBalances(accountTxs)
                    ?.let { error ->
                        throw CsvImportValidationException(
                            "股票 $code、帳戶 $accountId 的$error；${describeCsvImportRows(
                                importedForCode.filter { it.transaction.accountId == accountId }
                            )}；匯入被拒絕。"
                        )
                    }
                if (!com.rsps1008.stockify.data.MarginCalculationSupport.hasValidRepaymentBalances(accountTxs)) {
                    throw CsvImportValidationException(
                        "股票 $code、帳戶 $accountId 包含超過剩餘本金的融資還款；${describeCsvImportRows(
                            importedForCode.filter { it.transaction.accountId == accountId }
                        )}；匯入被拒絕。"
                    )
                }
                if (!com.rsps1008.stockify.data.ShortSellingCalculationSupport.hasValidCoverBalances(accountTxs)) {
                    throw CsvImportValidationException(
                        "股票 $code、帳戶 $accountId 包含超過剩餘股數或無效批次的融券操作；${describeCsvImportRows(
                            importedForCode.filter { it.transaction.accountId == accountId }
                        )}；匯入被拒絕。"
                    )
                }
            }
        }
    }

    private suspend fun writeImportedTransactions(
        transactions: List<CsvTransaction>,
        repairStockKeys: List<StockKey> = emptyList()
    ): Set<StockKey> {
        // importCsvTransactions canonicalizes rows before validation. Keep the
        // explicit market from a forced restore instead of inferring it again.
        val canonicalTransactions = transactions
        val importedTransactionKeys = canonicalTransactions
            .map { StockKey(it.market, it.stockCode) }
            .distinctBy { it.cacheKey() }
        val stockKeys = (importedTransactionKeys + repairStockKeys)
            .distinctBy { it.cacheKey() }
        val repairedStockKeys = repairImportedStockIdentities(stockKeys).toMutableSet()
        val newStocksByKey = linkedMapOf<String, Stock>()

        importedTransactionKeys.forEach { stockKey ->
            val code = stockKey.normalizedCode
            val inferredMarket = stockKey.normalizedMarket
            val existingStock = stockDao.getStockByCode(code, inferredMarket)
            if (existingStock == null) {
                val stockName = canonicalTransactions.firstOrNull {
                    canonicalStockCode(it.stockCode) == code && StockMarket.normalize(it.market) == inferredMarket
                }?.stockName.orEmpty()
                newStocksByKey.putIfAbsent(
                    stockKey.cacheKey(),
                    Stock(
                        name = stockName,
                        code = code,
                        market = inferredMarket
                    )
                )
            }
        }

        if (newStocksByKey.isNotEmpty()) {
            stockDao.insertStocks(newStocksByKey.values.toList())
        }

        val accountIds = canonicalTransactions.map { it.transaction.accountId }.distinct()
        val existingAccountsById = accountIds
            .chunked(SQLITE_IN_CHUNK_SIZE)
            .flatMap { stockDao.getAccountsByIds(it) }
            .associateBy { it.id }
        val newAccounts = accountIds
            .filterNot(existingAccountsById::containsKey)
            .map { accountId -> Account(id = accountId, name = "未命名帳戶 ($accountId)") }
        if (newAccounts.isNotEmpty()) {
            stockDao.insertAccounts(newAccounts)
        }

        stockDao.insertTransactions(canonicalTransactions.map { it.transaction })
        return canonicalTransactions
            .mapTo(repairedStockKeys) { StockKey(it.market, it.stockCode) }
    }

    /** Repairs legacy market/code values before an import can create a second logical stock. */
    private suspend fun repairImportedStockIdentities(stockKeys: Collection<StockKey>): Set<StockKey> {
        val targetKeys = stockKeys
            .map { StockKey(it.normalizedMarket, it.normalizedCode) }
            .distinctBy { it.cacheKey() }
        if (targetKeys.isEmpty()) return emptySet()

        val existingStocksByCode = targetKeys
            .map { it.normalizedCode }
            .distinct()
            .chunked(SQLITE_IN_CHUNK_SIZE)
            .flatMap { codes -> stockDao.getStocksByCodesForImportRepair(codes) }
            .groupBy { canonicalStockCode(it.code) }
        val repairedStockKeys = linkedSetOf<StockKey>()

        suspend fun mergeHistoryPrices(legacyStock: Stock, targetKey: StockKey) {
            val sourcePrices = stockDao.getHistoryPricesForImportRepair(
                stockCode = legacyStock.code,
                market = legacyStock.market
            )
            if (sourcePrices.isNotEmpty()) {
                val targetDates = stockDao.getHistoryPricesForImportRepair(
                    stockCode = targetKey.normalizedCode,
                    market = targetKey.normalizedMarket
                ).mapTo(hashSetOf()) { it.date }
                val missingTargetPrices = sourcePrices
                    .filterNot { it.date in targetDates }
                    .map {
                        it.copy(
                            stockCode = targetKey.normalizedCode,
                            market = targetKey.normalizedMarket
                        )
                    }
                if (missingTargetPrices.isNotEmpty()) {
                    stockDao.insertHistoryPrices(missingTargetPrices)
                }
            }
            stockDao.deleteHistoryPricesForImportRepair(legacyStock.code, legacyStock.market)
        }

        suspend fun mergeLegacyStock(legacyStock: Stock, targetKey: StockKey) {
            mergeHistoryPrices(legacyStock, targetKey)
            stockDao.updateTransactionStockIdentity(
                fromCode = canonicalStockCode(legacyStock.code),
                fromMarket = legacyStock.market,
                toCode = targetKey.normalizedCode,
                toMarket = targetKey.normalizedMarket
            )
            stockDao.deleteHistoryPricesForImportRepair(legacyStock.code, legacyStock.market)
            stockDao.deleteStockByCodeAndMarket(legacyStock.code, legacyStock.market)
        }

        for (targetKey in targetKeys) {
            val existingStocks = existingStocksByCode[targetKey.normalizedCode].orEmpty()
                .distinctBy { it.id }
            val canonicalStock = existingStocks.firstOrNull {
                it.market == targetKey.normalizedMarket &&
                    canonicalStockCode(it.code) == targetKey.normalizedCode &&
                    it.code == targetKey.normalizedCode
            }

            if (canonicalStock != null) {
                existingStocks
                    .filter { it.id != canonicalStock.id }
                    .forEach { mergeLegacyStock(it, targetKey) }
                if (existingStocks.any { it.id != canonicalStock.id }) {
                    repairedStockKeys += targetKey
                }
            } else {
                val primaryStock = existingStocks.firstOrNull() ?: continue
                mergeHistoryPrices(primaryStock, targetKey)
                stockDao.updateTransactionStockIdentity(
                    fromCode = canonicalStockCode(primaryStock.code),
                    fromMarket = primaryStock.market,
                    toCode = targetKey.normalizedCode,
                    toMarket = targetKey.normalizedMarket
                )
                stockDao.deleteHistoryPricesForImportRepair(primaryStock.code, primaryStock.market)
                stockDao.updateStock(
                    primaryStock.copy(
                        code = targetKey.normalizedCode,
                        market = targetKey.normalizedMarket
                    )
                )
                existingStocks
                    .drop(1)
                    .forEach { mergeLegacyStock(it, targetKey) }
                repairedStockKeys += targetKey
            }
        }
        return repairedStockKeys
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
        stockDao.replaceAccounts(accountsForReplacementRestore(restoredAccounts))
    }

    fun updateStockListFromTwse() {
        viewModelScope.launch {
            _isLoading.value = true
            _updatingStockListMarket.value = StockMarket.TW
            try {
                val started = StockListSyncCoordinator.runIfNotRunning(StockMarket.TW) {
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
                }
                if (!started) {
                    _message.value = "台股股票列表正在更新中，請稍後再試"
                }
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
                val started = StockListSyncCoordinator.runIfNotRunning(StockMarket.US) {
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
                }
                if (!started) {
                    _message.value = "美股股票列表正在更新中，請稍後再試"
                }
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
