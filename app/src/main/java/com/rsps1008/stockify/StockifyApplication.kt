package com.rsps1008.stockify

import android.app.Application
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.api.services.drive.DriveScopes
import com.rsps1008.stockify.data.CsvService
import com.rsps1008.stockify.data.GoogleDriveBackupBundle
import com.rsps1008.stockify.data.GoogleDriveService
import com.rsps1008.stockify.data.GoogleDriveAuthState
import com.rsps1008.stockify.data.GoogleDriveAuthResolutionHelper
import com.rsps1008.stockify.data.HoldingsOrderBackupService
import com.rsps1008.stockify.data.AppDatabase
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockDataFetcher
import com.rsps1008.stockify.data.StockListRepository
import com.rsps1008.stockify.data.StockListSyncCoordinator
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.TaiwanWeightedIndexService
import com.rsps1008.stockify.data.TransactionListRepository
import com.rsps1008.stockify.data.UsdTwdExchangeRateService
import com.rsps1008.stockify.data.TwseStockHistoryService
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.rsps1008.stockify.data.AutomaticCloudBackupGate
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream

private const val STOCK_LIST_UPDATE_INTERVAL_MILLIS = 7 * 24 * 60 * 60 * 1000L


class StockifyApplication : Application() {
    lateinit var database: AppDatabase
    lateinit var settingsDataStore: SettingsDataStore
    lateinit var exchangeRateService: UsdTwdExchangeRateService
    lateinit var realtimeStockDataService: RealtimeStockDataService
    lateinit var taiwanWeightedIndexService: TaiwanWeightedIndexService
    lateinit var twseStockHistoryService: TwseStockHistoryService
    lateinit var transactionListRepository: TransactionListRepository
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val automaticCloudBackupGate = AutomaticCloudBackupGate()

    suspend fun performAutomaticCloudBackupIfDue(): Boolean {
        return automaticCloudBackupGate.runIfIdle {
            performAutomaticCloudBackupIfDueInternal()
        }
    }

    private suspend fun performAutomaticCloudBackupIfDueInternal(): Boolean {
        if (!settingsDataStore.autoCloudBackupEnabledFlow.first()) return true
        val now = System.currentTimeMillis()
        if (!settingsDataStore.claimAutomaticCloudBackupAttempt(now)) return true
        var savedEmail = settingsDataStore.googleAccountEmailFlow.first()
        if (savedEmail.isNullOrBlank()) {
            val legacyAccount = com.rsps1008.stockify.data.GoogleDriveAccount.fromLegacyStorage(this@StockifyApplication)
            if (legacyAccount != null) {
                savedEmail = legacyAccount.email
                settingsDataStore.setGoogleAccountEmail(savedEmail)
            }
        }
        val authVersion = settingsDataStore.googleDriveAuthVersionFlow.first()
        val attemptTime = settingsDataStore.beginGoogleDriveAuthValidationIfMatching(
            expectedEmail = savedEmail,
            expectedVersion = authVersion
        ) ?: return true
        if (savedEmail.isNullOrBlank()) {
            settingsDataStore.setGoogleDriveAuthStateIfMatching(
                expectedEmail = null,
                expectedVersion = authVersion,
                state = GoogleDriveAuthState.NOT_SIGNED_IN,
                lastError = "尚未登入 Google Drive，無法自動備份",
                attemptTime = attemptTime
            )
            return false
        }
        val driveScope = Scope(DriveScopes.DRIVE_APPDATA)
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope, Scope("email")))
            .setAccount(android.accounts.Account(savedEmail, "com.google"))
            .build()
        val authResult = try {
            withContext(Dispatchers.IO) {
                Tasks.await(Identity.getAuthorizationClient(this@StockifyApplication).authorize(request))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to verify Google Drive authorization", e)
            null
        }
        if (authResult == null) {
            settingsDataStore.setGoogleDriveAuthStateIfMatching(
                expectedEmail = savedEmail,
                expectedVersion = authVersion,
                state = GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE,
                lastError = "Google Drive 授權驗證失敗（網路或連線問題）",
                attemptTime = attemptTime
            )
            return false
        }
        if (authResult.hasResolution()) {
            settingsDataStore.setGoogleDriveAuthStateIfMatching(
                expectedEmail = savedEmail,
                expectedVersion = authVersion,
                state = GoogleDriveAuthState.NEEDS_REAUTHORIZATION,
                lastError = "Google Drive 需要重新確認授權，請開啟 App 重新授權",
                attemptTime = attemptTime
            )
            return false
        }
        if (!authResult.grantedScopes.any { it.contains("drive.appdata") }) {
            settingsDataStore.setGoogleDriveAuthStateIfMatching(
                expectedEmail = savedEmail,
                expectedVersion = authVersion,
                state = GoogleDriveAuthState.NEEDS_REAUTHORIZATION,
                lastError = "Google Drive 尚未取得備份權限，請開啟 App 重新授權",
                attemptTime = attemptTime
            )
            return false
        }
        val validationUpdated = settingsDataStore.setGoogleDriveAuthStateIfMatching(
            expectedEmail = savedEmail,
            expectedVersion = authVersion,
            state = GoogleDriveAuthState.AUTHORIZED,
            attemptTime = attemptTime
        )
        if (!validationUpdated) return true

        val bundle = try {
            val transactions = database.stockDao().getTransactionsWithStock().first()
            val csvContent = withContext(Dispatchers.IO) {
                ByteArrayOutputStream().use { output ->
                    CsvService().export(transactions, output)
                    output.toByteArray()
                }
            }
            val accountsJson = Json.encodeToString(database.stockDao().getAllAccountsFlow().first())
                .toByteArray(Charsets.UTF_8)
            val order = settingsDataStore.holdingsOrderFlow.first()
            val realizedOrder = settingsDataStore.realizedHoldingsOrderFlow.first()
            val orderBytes = withContext(Dispatchers.IO) {
                HoldingsOrderBackupService().exportToBytes(order, realizedOrder)
            }
            withContext(Dispatchers.Default) {
                GoogleDriveBackupBundle.create(csvContent, accountsJson, orderBytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prepare backup bundle data", e)
            settingsDataStore.setAutoCloudBackupLastErrorIfMatching(
                expectedEmail = savedEmail,
                expectedVersion = authVersion,
                attemptTime = attemptTime,
                message = "建立備份資料失敗: ${e.localizedMessage ?: e.javaClass.simpleName}",
                failedAt = now
            )
            return false
        }

        return try {
            val driveService = GoogleDriveService(this, savedEmail)
            driveService.uploadBackup(
                fileName = AUTO_CLOUD_BACKUP_FILE_NAME,
                content = bundle,
                mimeType = "application/zip"
            ).getOrThrow()
            val localSuccessAt = System.currentTimeMillis()
            settingsDataStore.setGoogleDriveAuthStateIfMatching(
                expectedEmail = savedEmail,
                expectedVersion = authVersion,
                state = GoogleDriveAuthState.AUTHORIZED,
                clearLastError = true,
                attemptTime = attemptTime,
                localBackupSuccessAt = localSuccessAt
            )
            // 上傳已成功；修改時間查詢失敗時保留快取，待畫面再次同步。
            val cloudModifiedTime = driveService.getBackupModifiedTime(AUTO_CLOUD_BACKUP_FILE_NAME).getOrNull()
            settingsDataStore.setGoogleDriveAuthStateIfMatching(
                expectedEmail = savedEmail,
                expectedVersion = authVersion,
                state = GoogleDriveAuthState.AUTHORIZED,
                clearLastError = true,
                attemptTime = attemptTime,
                backupSuccessAt = cloudModifiedTime
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failedAt = System.currentTimeMillis()
            Log.w(TAG, "Automatic Google Drive backup failed during upload", e)
            val uploadAuthState = GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(e)
            if (uploadAuthState != null) {
                settingsDataStore.setGoogleDriveAuthStateIfMatching(
                    expectedEmail = savedEmail,
                    expectedVersion = authVersion,
                    state = uploadAuthState,
                    lastError = e.localizedMessage ?: e.javaClass.simpleName,
                    attemptTime = attemptTime
                )
            } else {
                settingsDataStore.setAutoCloudBackupLastErrorIfMatching(
                    expectedEmail = savedEmail,
                    expectedVersion = authVersion,
                    attemptTime = attemptTime,
                    message = e.localizedMessage ?: e.javaClass.simpleName,
                    failedAt = failedAt
                )
            }
            false
        }
    }
    // ★ 新增：全域 HttpClient（給 TWSE / 即時股價 / 配息用）

    val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
        }
    }

    /**
     * Checks the Taiwan stock list once when the app opens. This deliberately does not
     * schedule background work or surface a user-facing message.
    */
    suspend fun updateTaiwanStockListIfDue() {
        StockListSyncCoordinator.runIfNotRunning(StockMarket.TW) {
            updateTaiwanStockListIfDueInternal()
        }
    }

    private suspend fun updateTaiwanStockListIfDueInternal() {
        val lastUpdatedAt = settingsDataStore.lastStockListUpdateTimeFlow.first()
        if (lastUpdatedAt != null && System.currentTimeMillis() - lastUpdatedAt < STOCK_LIST_UPDATE_INTERVAL_MILLIS) {
            return
        }

        try {
            val stocks = StockDataFetcher(httpClient).fetchStockList()
            if (stocks.isEmpty()) {
                Log.w(TAG, "Taiwan stock list update returned no stocks")
                return
            }

            val stockListRepository = StockListRepository(this)
            val updatedStocks = stockListRepository.replaceStocksInDatabase(
                database = database,
                market = StockMarket.TW,
                stocks = stocks
            )
            try {
                stockListRepository.saveStocks(updatedStocks)
            } catch (e: Exception) {
                Log.w(TAG, "Taiwan stock list database updated, but local cache save failed", e)
            }
            settingsDataStore.setLastStockListUpdateTime(System.currentTimeMillis())
            Log.i(TAG, "Updated Taiwan stock list on app open: ${updatedStocks.size} stocks")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update Taiwan stock list on app open", e)
        }
    }

    /**
     * Checks the U.S. stock list once when the app opens. This deliberately does not
     * schedule background work or surface a user-facing message.
    */
    suspend fun updateUsStockListIfDue() {
        StockListSyncCoordinator.runIfNotRunning(StockMarket.US) {
            updateUsStockListIfDueInternal()
        }
    }

    private suspend fun updateUsStockListIfDueInternal() {
        val lastUpdatedAt = settingsDataStore.lastUsStockListUpdateTimeFlow.first()
        if (lastUpdatedAt != null && System.currentTimeMillis() - lastUpdatedAt < STOCK_LIST_UPDATE_INTERVAL_MILLIS) {
            return
        }

        try {
            val stocks = StockDataFetcher(httpClient).fetchUsStockList()
            if (stocks.isEmpty()) {
                Log.w(TAG, "U.S. stock list update returned no stocks")
                return
            }

            val updatedStocks = StockListRepository(this).replaceStocksInDatabase(
                database = database,
                market = StockMarket.US,
                stocks = stocks
            )
            settingsDataStore.setLastUsStockListUpdateTime(System.currentTimeMillis())
            Log.i(TAG, "Updated U.S. stock list on app open: ${updatedStocks.size} stocks")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update U.S. stock list on app open", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        database = AppDatabase.getDatabase(this)
        settingsDataStore = SettingsDataStore(this)
        exchangeRateService = UsdTwdExchangeRateService(settingsDataStore)
        taiwanWeightedIndexService = TaiwanWeightedIndexService(httpClient, settingsDataStore)
        realtimeStockDataService = RealtimeStockDataService(
            stockDao = database.stockDao(),
            settingsDataStore = settingsDataStore,
            taiwanWeightedIndexService = taiwanWeightedIndexService,
            applicationContext = this
        )
        twseStockHistoryService = TwseStockHistoryService(httpClient, database.stockDao())
        transactionListRepository = TransactionListRepository(
            stockDao = database.stockDao(),
            activeAccountIdFlow = settingsDataStore.activeAccountIdFlow,
            applicationScope = applicationScope
        )
    }

    private companion object {
        const val TAG = "StockifyApplication"
    }
}

const val AUTO_CLOUD_BACKUP_FILE_NAME = "stockify_auto_backup_bundle.zip"
