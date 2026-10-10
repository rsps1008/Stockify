package com.rsps1008.stockify.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rsps1008.stockify.data.dividend.DividendInfoCacheEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

internal fun isAutomaticCloudBackupDue(
    enabled: Boolean,
    intervalDays: Int,
    lastAttempt: Long,
    lastSuccess: Long,
    hasRecordedFailure: Boolean,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    retryCooldownMillis: Long = 5 * 60 * 1000L
): Boolean {
    if (!enabled) return false

    if (hasRecordedFailure && lastAttempt > 0L && (nowMillis - lastAttempt) in 0 until retryCooldownMillis) {
        return false
    }

    return when {
        intervalDays == 0 -> true
        lastSuccess == 0L -> {
            lastAttempt == 0L || (nowMillis - lastAttempt) >= retryCooldownMillis
        }
        else -> {
            val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
            val lastSuccessDate = Instant.ofEpochMilli(lastSuccess).atZone(zoneId).toLocalDate()
            val daysSinceSuccess = ChronoUnit.DAYS.between(lastSuccessDate, nowDate)
            daysSinceSuccess >= intervalDays || daysSinceSuccess < 0
        }
    }
}

class SettingsDataStore private constructor(
    val context: Context?,
    private val dataStoreInstance: DataStore<Preferences>
) {
    constructor(context: Context) : this(context, context.dataStore)
    constructor(dataStore: DataStore<Preferences>) : this(null, dataStore)

    companion object {
        private val dividendCacheMutex = Mutex()
        private val sequenceGenerator = AtomicLong(System.currentTimeMillis())

        fun syncSequenceWithPersisted(persistedSequence: Long) {
            sequenceGenerator.updateAndGet { current ->
                maxOf(current, persistedSequence)
            }
        }

        fun nextSequence(): Long {
            while (true) {
                val current = sequenceGenerator.get()
                val now = System.currentTimeMillis()
                val next = if (now > current) now else current + 1
                if (sequenceGenerator.compareAndSet(current, next)) {
                    return next
                }
            }
        }

        internal fun resetSequenceForTesting(value: Long) {
            sequenceGenerator.set(value)
        }

        fun parseGoogleDriveAuthState(raw: String?, email: String?): GoogleDriveAuthState {
            if (email.isNullOrBlank()) {
                return GoogleDriveAuthState.NOT_SIGNED_IN
            }
            if (raw != null) {
                try {
                    return GoogleDriveAuthState.valueOf(raw)
                } catch (_: IllegalArgumentException) {
                    // Safe fallback for unknown enum value
                }
            }
            return GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val fetchIntervalKey = intPreferencesKey("refresh_interval")
    private val lastStockListUpdateTimeKey = longPreferencesKey("last_stock_list_update_time")
    private val lastUsStockListUpdateTimeKey = longPreferencesKey("last_us_stock_list_update_time")
    private val feeDiscountKey = doublePreferencesKey("fee_discount")
    private val minFeeRegularKey = intPreferencesKey("min_fee_regular")
    private val minFeeOddLotKey = intPreferencesKey("min_fee_odd_lot")
    private val dividendFeeKey = intPreferencesKey("dividend_fee")
    private val preDeductSellFeesKey = booleanPreferencesKey("pre_deduct_sell_fees")
    private val partialSalesAsRealizedKey = booleanPreferencesKey("partial_sales_as_realized")
    private val excludeDividendIncomeFromReturnsKey = booleanPreferencesKey("exclude_dividend_income_from_returns")
    private val useCumulativeReturnRateKey = booleanPreferencesKey("use_cumulative_return_rate")
    private val returnRateModeKey = stringPreferencesKey("return_rate_mode")
    private val realtimeStockInfoCacheKey = stringPreferencesKey("realtime_stock_info_cache")
    private val themeKey = stringPreferencesKey("theme")
    private val textSizeModeKey = stringPreferencesKey("text_size_mode")
    private val stockDataSourceKey = stringPreferencesKey("stock_data_source")
    private val usStockDataSourceKey = stringPreferencesKey("us_stock_data_source")
    // 保留原 key，以便既有設定升級後可直接採用新的提示語意。
    private val fallbackNoticeEnabledKey = booleanPreferencesKey("notify_fallback_repeatedly")
    private val taxRateNormalListedStockKey = doublePreferencesKey("tax_rate_normal_listed_stock")
    private val taxRateDomesticStockEtfKey = doublePreferencesKey("tax_rate_domestic_stock_etf")
    private val taxRateBondEtfKey = doublePreferencesKey("tax_rate_bond_etf")
    private val taxRateDayTradingKey = doublePreferencesKey("tax_rate_day_trading")
    private val skipPdfImportTutorialKey = booleanPreferencesKey("skip_pdf_import_tutorial")
    private val usdToTwdRateKey = doublePreferencesKey("usd_to_twd_rate")
    private val usdToTwdRateUpdatedAtKey = longPreferencesKey("usd_to_twd_rate_updated_at")
    private val taiwanWeightedIndexCacheKey = stringPreferencesKey("taiwan_weighted_index_cache")
    private val dividendInfoCacheKey = stringPreferencesKey("dividend_info_cache")
    private val homeDisplayModeKey = stringPreferencesKey("home_display_mode")
    private val holdingsOrderKey = stringPreferencesKey("holdings_order")
    private val realizedHoldingsOrderKey = stringPreferencesKey("realized_holdings_order")
    private val holdingsReorderHintShownKey = booleanPreferencesKey("holdings_reorder_hint_shown")
    private val homeHoldingsSortModeKey = stringPreferencesKey("home_holdings_sort_mode")
    private val homeHoldingsSortColumnKey = stringPreferencesKey("home_holdings_sort_column")
    private val homeHoldingsSortAscendingKey = booleanPreferencesKey("home_holdings_sort_ascending")
    private val localCsvRestoreFeeHintShownKey = booleanPreferencesKey("local_csv_restore_fee_hint_shown")
    private val calculationRoundingModeKey = stringPreferencesKey("calculation_rounding_mode")
    private val activeAccountIdKey = intPreferencesKey("active_account_id")
    private val showTaiwanWeightedIndexKey = booleanPreferencesKey("show_taiwan_weighted_index")
    private val showTaiwanPortfolioChartKey = booleanPreferencesKey("show_taiwan_portfolio_chart")
    private val homeHistoryChartExpandedKey = booleanPreferencesKey("home_history_chart_expanded")
    private val detailHistoryChartExpandedKey = booleanPreferencesKey("detail_history_chart_expanded")
    private val cloudDataBackupUpdatedAtKey = longPreferencesKey("cloud_data_backup_updated_at")
    private val autoCloudBackupEnabledKey = booleanPreferencesKey("auto_cloud_backup_enabled")
    private val autoCloudBackupIntervalDaysKey = intPreferencesKey("auto_cloud_backup_interval_days")
    private val autoCloudBackupLastAttemptAtKey = longPreferencesKey("auto_cloud_backup_last_attempt_at")
    private val autoCloudBackupLastSuccessAtKey = longPreferencesKey("auto_cloud_backup_last_success_at")
    private val autoCloudBackupLastLocalSuccessAtKey = longPreferencesKey("auto_cloud_backup_last_local_success_at")
    private val autoCloudBackupLastErrorKey = stringPreferencesKey("auto_cloud_backup_last_error")
    private val googleAccountEmailKey = stringPreferencesKey("google_account_email")
    private val googleDriveAuthStateKey = stringPreferencesKey("google_drive_auth_state")
    private val googleDriveAuthVersionKey = longPreferencesKey("google_drive_auth_version")
    private val googleDriveAuthValidationTimeKey = longPreferencesKey("google_drive_auth_validation_time")
    private val marginFeatureEnabledKey = booleanPreferencesKey("margin_feature_enabled")
    private val marginDayCountKey = intPreferencesKey("margin_day_count")
    private val defaultMarginAnnualRateKey = doublePreferencesKey("default_margin_annual_rate")
    private val defaultShortBorrowAnnualRateKey = doublePreferencesKey("default_short_borrow_annual_rate")
    private val bankDepositsKey = stringPreferencesKey("bank_deposits")
    private val loansKey = stringPreferencesKey("asset_overview_loans")
    private val appLockEnabledKey = booleanPreferencesKey("app_lock_enabled")
    private val appLockPinSaltKey = stringPreferencesKey("app_lock_pin_salt")
    private val appLockPinHashKey = stringPreferencesKey("app_lock_pin_hash")
    private val appLockBiometricEnabledKey = booleanPreferencesKey("app_lock_biometric_enabled")
    private val lastUpdateHighlightsVersionKey = stringPreferencesKey("last_update_highlights_version")

    internal suspend fun getTaiwanHolidayCalendar(year: Int): TaiwanHolidayCalendarSnapshot? {
        val key = stringPreferencesKey("taiwan_holiday_calendar_$year")
        val raw = dataStoreInstance.data.first()[key] ?: return null
        val snapshot = runCatching { json.decodeFromString<TaiwanHolidayCalendarSnapshot>(raw) }.getOrNull()
            ?.takeIf { it.year == year && it.toDateMap() != null }
        if (snapshot != null) return snapshot

        // Accept the earlier map-only cache format as version 0, then rewrite it when a newer
        // bundled calendar is available.
        val legacy = runCatching { json.decodeFromString<Map<String, Boolean>>(raw) }.getOrNull() ?: return null
        val dates = legacy.mapNotNull { (date, isHoliday) ->
            runCatching { LocalDate.parse(date) }.getOrNull()?.takeIf { it.year == year }
                ?.let { it.toString() to isHoliday }
        }.toMap()
        return TaiwanHolidayCalendarSnapshot(year, 0, 0L, dates, "legacy")
            .takeIf { dates.size == legacy.size && it.toDateMap() != null }
    }

    internal suspend fun setTaiwanHolidayCalendar(calendar: TaiwanHolidayCalendarSnapshot) {
        require(calendar.toDateMap() != null) { "Taiwan holiday calendar is incomplete or invalid" }
        val key = stringPreferencesKey("taiwan_holiday_calendar_${calendar.year}")
        dataStoreInstance.edit { it[key] = json.encodeToString(calendar) }
    }

    val fetchIntervalFlow: Flow<Int> = dataStoreInstance.data
        .map { preferences ->
            preferences[fetchIntervalKey] ?: 10
        }

    val lastStockListUpdateTimeFlow: Flow<Long?> = dataStoreInstance.data
        .map { preferences ->
            preferences[lastStockListUpdateTimeKey]
        }

    val lastUsStockListUpdateTimeFlow: Flow<Long?> = dataStoreInstance.data
        .map { preferences ->
            preferences[lastUsStockListUpdateTimeKey]
        }

    val feeDiscountFlow: Flow<Double> = dataStoreInstance.data
        .map { preferences ->
            preferences[feeDiscountKey] ?: 0.28
        }

    val minFeeRegularFlow: Flow<Int> = dataStoreInstance.data
        .map { preferences ->
            preferences[minFeeRegularKey] ?: 1
        }

    val minFeeOddLotFlow: Flow<Int> = dataStoreInstance.data
        .map { preferences ->
            preferences[minFeeOddLotKey] ?: 1
        }
    val dividendFeeFlow: Flow<Int> = dataStoreInstance.data
        .map { preferences ->
            preferences[dividendFeeKey] ?: 10
        }
    
    val preDeductSellFeesFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[preDeductSellFeesKey] ?: true
        }

    val partialSalesAsRealizedFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[partialSalesAsRealizedKey] ?: false
        }

    val excludeDividendIncomeFromReturnsFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[excludeDividendIncomeFromReturnsKey] ?: false
        }

    val lastUpdateHighlightsVersionFlow: Flow<String?> = dataStoreInstance.data
        .map { preferences ->
            preferences[lastUpdateHighlightsVersionKey]
        }

    val returnRateModeFlow: Flow<ReturnRateMode> = dataStoreInstance.data
        .map { preferences ->
            val rawMode = preferences[returnRateModeKey]
            if (rawMode != null) {
                ReturnRateMode.normalize(rawMode)
            } else if (preferences[useCumulativeReturnRateKey] == true) {
                ReturnRateMode.CUMULATIVE_INVESTMENT
            } else {
                ReturnRateMode.REMAINING_POSITION
            }
        }

    val useCumulativeReturnRateFlow: Flow<Boolean> = returnRateModeFlow
        .map { it == ReturnRateMode.CUMULATIVE_INVESTMENT }

    val calculationRoundingModeFlow: Flow<String> = dataStoreInstance.data
        .map { preferences ->
            CalculationRoundingMode.normalize(preferences[calculationRoundingModeKey])
        }

    val realtimeStockInfoCacheFlow: Flow<Map<String, RealtimeStockInfo>> = dataStoreInstance.data
        .map { preferences ->
            preferences[realtimeStockInfoCacheKey]
                ?.let { JsonCacheDecodeSupport.decodeOrNull<Map<String, RealtimeStockInfo>>(it) }
                ?: emptyMap()
        }
    
    val themeFlow: Flow<String> = dataStoreInstance.data
        .map { preferences ->
            preferences[themeKey] ?: "System"
        }

    val textSizeModeFlow: Flow<String> = dataStoreInstance.data
        .map { preferences ->
            preferences[textSizeModeKey] ?: TextSizeMode.DEFAULT
        }

    val stockDataSourceFlow: Flow<String> = dataStoreInstance.data
        .map { preferences ->
            preferences[stockDataSourceKey] ?: "TWSE"
        }

    val usStockDataSourceFlow: Flow<String> = dataStoreInstance.data
        .map { preferences ->
            preferences[usStockDataSourceKey] ?: "Nasdaq"
        }

    val fallbackNoticeEnabledFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[fallbackNoticeEnabledKey] ?: false
        }

    val taxRateNormalListedStockFlow: Flow<Double> = dataStoreInstance.data
        .map { preferences ->
            preferences[taxRateNormalListedStockKey] ?: 0.003
        }

    val taxRateDomesticStockEtfFlow: Flow<Double> = dataStoreInstance.data
        .map { preferences ->
            preferences[taxRateDomesticStockEtfKey] ?: 0.001
        }

    val taxRateBondEtfFlow: Flow<Double> = dataStoreInstance.data
        .map { preferences ->
            preferences[taxRateBondEtfKey] ?: 0.0
        }

    val taxRateDayTradingFlow: Flow<Double> = dataStoreInstance.data
        .map { preferences ->
            preferences[taxRateDayTradingKey] ?: 0.0015
        }

    val skipPdfImportTutorialFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[skipPdfImportTutorialKey] ?: false
        }

    val usdToTwdRateFlow: Flow<Double> = dataStoreInstance.data
        .map { preferences ->
            preferences[usdToTwdRateKey] ?: 32.0
        }

    val usdToTwdRateUpdatedAtFlow: Flow<Long?> = dataStoreInstance.data
        .map { preferences ->
            preferences[usdToTwdRateUpdatedAtKey]
        }

    val taiwanWeightedIndexCacheFlow: Flow<TaiwanWeightedIndexInfo?> = dataStoreInstance.data
        .map { preferences ->
            preferences[taiwanWeightedIndexCacheKey]
                ?.let { JsonCacheDecodeSupport.decodeOrNull<TaiwanWeightedIndexInfo>(it) }
        }

    val dividendInfoCacheFlow: Flow<Map<String, DividendInfoCacheEntry>> = dataStoreInstance.data
        .map { preferences ->
            preferences[dividendInfoCacheKey]
                ?.let { raw ->
                    runCatching {
                        json.decodeFromString<Map<String, DividendInfoCacheEntry>>(raw)
                    }.getOrDefault(emptyMap())
                }
                ?.also { cache ->
                    val maxPersistedSeq = cache.values.maxOfOrNull {
                        it.requestSequence ?: it.lastFetchedTimeMillis ?: 0L
                    } ?: 0L
                    syncSequenceWithPersisted(maxPersistedSeq)
                }
                ?: emptyMap()
        }

    val homeDisplayModeFlow: Flow<String> = dataStoreInstance.data
        .map { preferences ->
            preferences[homeDisplayModeKey] ?: HomeDisplayMode.COMBINED
        }

    val holdingsOrderFlow: Flow<List<String>> = dataStoreInstance.data
        .map { preferences ->
            preferences[holdingsOrderKey]
                ?.split("|")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }

    val realizedHoldingsOrderFlow: Flow<List<String>> = dataStoreInstance.data
        .map { preferences ->
            preferences[realizedHoldingsOrderKey]
                ?.split("|")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }

    val holdingsReorderHintShownFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[holdingsReorderHintShownKey] ?: false
        }

    val homeHoldingsSortModeFlow: Flow<String> = dataStoreInstance.data
        .map { preferences ->
            preferences[homeHoldingsSortModeKey] ?: "MANUAL"
        }

    val homeHoldingsSortColumnFlow: Flow<String> = dataStoreInstance.data
        .map { preferences ->
            preferences[homeHoldingsSortColumnKey] ?: "NONE"
        }

    val homeHoldingsSortAscendingFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[homeHoldingsSortAscendingKey] ?: true
        }

    val localCsvRestoreFeeHintShownFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[localCsvRestoreFeeHintShownKey] ?: false
        }

    val activeAccountIdFlow: Flow<Int> = dataStoreInstance.data
        .map { preferences ->
            preferences[activeAccountIdKey] ?: 0
        }

    val showTaiwanWeightedIndexFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[showTaiwanWeightedIndexKey] ?: true
        }

    val showTaiwanPortfolioChartFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[showTaiwanPortfolioChartKey] ?: true
        }

    val homeHistoryChartExpandedFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[homeHistoryChartExpandedKey] ?: true
        }

    val detailHistoryChartExpandedFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[detailHistoryChartExpandedKey] ?: true
        }

    val cloudDataBackupUpdatedAtFlow: Flow<Long?> = dataStoreInstance.data
        .map { preferences ->
            preferences[cloudDataBackupUpdatedAtKey]
        }

    val autoCloudBackupEnabledFlow: Flow<Boolean> = dataStoreInstance.data
        .map { it[autoCloudBackupEnabledKey] ?: false }

    val autoCloudBackupIntervalDaysFlow: Flow<Int> = dataStoreInstance.data
        .map { it[autoCloudBackupIntervalDaysKey]?.takeIf { days -> days in setOf(0, 1, 3, 7) } ?: 1 }

    val autoCloudBackupLastSuccessAtFlow: Flow<Long?> = dataStoreInstance.data
        .map { it[autoCloudBackupLastSuccessAtKey] }

    val autoCloudBackupLastLocalSuccessAtFlow: Flow<Long?> = dataStoreInstance.data
        .map { it[autoCloudBackupLastLocalSuccessAtKey] }

    val autoCloudBackupLastAttemptAtFlow: Flow<Long?> = dataStoreInstance.data
        .map { it[autoCloudBackupLastAttemptAtKey] }

    val autoCloudBackupLastErrorFlow: Flow<String?> = dataStoreInstance.data
        .map { it[autoCloudBackupLastErrorKey] }

    val googleAccountEmailFlow: Flow<String?> = dataStoreInstance.data
        .map { it[googleAccountEmailKey] }

    val googleDriveAuthStateFlow: Flow<GoogleDriveAuthState> = dataStoreInstance.data
        .map { preferences ->
            parseGoogleDriveAuthState(
                raw = preferences[googleDriveAuthStateKey],
                email = preferences[googleAccountEmailKey]
            )
        }

    val googleDriveAuthVersionFlow: Flow<Long> = dataStoreInstance.data
        .map { preferences -> preferences[googleDriveAuthVersionKey] ?: 0L }

    val googleDriveAuthValidationTimeFlow: Flow<Long> = dataStoreInstance.data
        .map { preferences -> preferences[googleDriveAuthValidationTimeKey] ?: 0L }

    val marginFeatureEnabledFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences -> preferences[marginFeatureEnabledKey] ?: false }

    val marginDayCountFlow: Flow<Int> = dataStoreInstance.data
        .map { preferences -> if (preferences[marginDayCountKey] == 360) 360 else 365 }

    val defaultMarginAnnualRateFlow: Flow<Double> = dataStoreInstance.data
        .map { preferences ->
            preferences[defaultMarginAnnualRateKey]
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: DEFAULT_MARGIN_ANNUAL_RATE
        }

    val defaultShortBorrowAnnualRateFlow: Flow<Double> = dataStoreInstance.data
        .map { preferences ->
            preferences[defaultShortBorrowAnnualRateKey]
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: DEFAULT_SHORT_BORROW_ANNUAL_RATE
        }

    val bankDepositsFlow: Flow<List<BankDeposit>> = dataStoreInstance.data
        .map { preferences ->
            preferences[bankDepositsKey]
                ?.let { raw ->
                    runCatching { Json.decodeFromString<List<BankDeposit>>(raw) }
                        .getOrDefault(emptyList())
                }
                ?: emptyList()
        }

    val loansFlow: Flow<List<Loan>> = dataStoreInstance.data
        .map { preferences ->
            preferences[loansKey]
                ?.let { raw ->
                    runCatching { Json.decodeFromString<List<Loan>>(raw) }
                        .getOrDefault(emptyList())
                }
                ?: emptyList()
        }

    // Read both lists from the same preferences snapshot so a restore never exposes mixed balances.
    val assetBalancesFlow: Flow<AssetBalances> = dataStoreInstance.data
        .map { it[bankDepositsKey] to it[loansKey] }
        .distinctUntilChanged()
        .map { (deposits, loans) ->
            AssetBalances(
                deposits?.let { runCatching { Json.decodeFromString<List<BankDeposit>>(it) }.getOrDefault(emptyList()) }.orEmpty(),
                loans?.let { runCatching { Json.decodeFromString<List<Loan>>(it) }.getOrDefault(emptyList()) }.orEmpty()
            )
        }.flowOn(Dispatchers.Default)

    val appLockEnabledFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[appLockEnabledKey] == true &&
                !preferences[appLockPinSaltKey].isNullOrBlank() &&
                !preferences[appLockPinHashKey].isNullOrBlank()
        }

    val appLockBiometricEnabledFlow: Flow<Boolean> = dataStoreInstance.data
        .map { preferences ->
            preferences[appLockEnabledKey] == true &&
                preferences[appLockBiometricEnabledKey] == true
        }


    suspend fun setFetchInterval(interval: Int) {
        dataStoreInstance.edit {
            it[fetchIntervalKey] = interval
        }
    }

    suspend fun setLastStockListUpdateTime(time: Long) {
        dataStoreInstance.edit {
            it[lastStockListUpdateTimeKey] = time
        }
    }

    suspend fun setLastUsStockListUpdateTime(time: Long) {
        dataStoreInstance.edit {
            it[lastUsStockListUpdateTimeKey] = time
        }
    }

    suspend fun setFeeDiscount(discount: Double) {
        dataStoreInstance.edit {
            it[feeDiscountKey] = discount
        }
    }

    suspend fun setMinFeeRegular(fee: Int) {
        dataStoreInstance.edit {
            it[minFeeRegularKey] = fee
        }
    }

    suspend fun setMinFeeOddLot(fee: Int) {
        dataStoreInstance.edit {
            it[minFeeOddLotKey] = fee
        }
    }
    
    suspend fun setDividendFee(fee: Int) {
        dataStoreInstance.edit {
            it[dividendFeeKey] = fee
        }
    }

    suspend fun setPreDeductSellFees(preDeduct: Boolean) {
        dataStoreInstance.edit {
            it[preDeductSellFeesKey] = preDeduct
        }
    }

    suspend fun setReturnRateMode(mode: ReturnRateMode) {
        dataStoreInstance.edit {
            it[returnRateModeKey] = mode.key
            it[useCumulativeReturnRateKey] = mode == ReturnRateMode.CUMULATIVE_INVESTMENT
        }
    }

    suspend fun setUseCumulativeReturnRate(useCumulative: Boolean) {
        setReturnRateMode(if (useCumulative) ReturnRateMode.CUMULATIVE_INVESTMENT else ReturnRateMode.REMAINING_POSITION)
    }

    suspend fun setCalculationRoundingMode(mode: String) {
        dataStoreInstance.edit {
            it[calculationRoundingModeKey] = CalculationRoundingMode.normalize(mode)
        }
    }

    suspend fun setRealtimeStockInfoCache(cache: Map<String, RealtimeStockInfo>) {
        dataStoreInstance.edit {
            it[realtimeStockInfoCacheKey] = Json.encodeToString(cache)
        }
    }

    suspend fun clearRealtimeStockInfoCache() {
        dataStoreInstance.edit {
            it.remove(realtimeStockInfoCacheKey)
        }
    }

    suspend fun setTheme(theme: String) {
        dataStoreInstance.edit {
            it[themeKey] = theme
        }
    }

    suspend fun setTextSizeMode(mode: String) {
        dataStoreInstance.edit {
            it[textSizeModeKey] = TextSizeMode.normalize(mode)
        }
    }

    suspend fun setStockDataSource(source: String) {
        dataStoreInstance.edit {
            it[stockDataSourceKey] = source
        }
    }

    suspend fun setUsStockDataSource(source: String) {
        dataStoreInstance.edit {
            it[usStockDataSourceKey] = source
        }
    }

    suspend fun setFallbackNoticeEnabled(enabled: Boolean) {
        dataStoreInstance.edit {
            it[fallbackNoticeEnabledKey] = enabled
        }
    }

    suspend fun setTaxRateNormalListedStock(rate: Double) {
        dataStoreInstance.edit {
            it[taxRateNormalListedStockKey] = rate
        }
    }

    suspend fun setTaxRateDomesticStockEtf(rate: Double) {
        dataStoreInstance.edit {
            it[taxRateDomesticStockEtfKey] = rate
        }
    }

    suspend fun setTaxRateBondEtf(rate: Double) {
        dataStoreInstance.edit {
            it[taxRateBondEtfKey] = rate
        }
    }

    suspend fun setTaxRateDayTrading(rate: Double) {
        dataStoreInstance.edit {
            it[taxRateDayTradingKey] = rate
        }
    }

    suspend fun setSkipPdfImportTutorial(skip: Boolean) {
        dataStoreInstance.edit {
            it[skipPdfImportTutorialKey] = skip
        }
    }

    suspend fun setUsdToTwdRate(rate: Double, updatedAt: Long = System.currentTimeMillis()) {
        dataStoreInstance.edit {
            it[usdToTwdRateKey] = rate
            it[usdToTwdRateUpdatedAtKey] = updatedAt
        }
    }

    suspend fun setTaiwanWeightedIndexCache(info: TaiwanWeightedIndexInfo) {
        dataStoreInstance.edit {
            it[taiwanWeightedIndexCacheKey] = Json.encodeToString(info)
        }
    }

    suspend fun setDividendInfoCacheEntry(
        stockCode: String,
        entry: DividendInfoCacheEntry
    ) {
        dividendCacheMutex.withLock {
            dataStoreInstance.edit { preferences ->
                val currentCache = preferences[dividendInfoCacheKey]
                    ?.let { raw ->
                        runCatching {
                            json.decodeFromString<Map<String, DividendInfoCacheEntry>>(raw)
                        }.getOrDefault(emptyMap())
                    }
                    .orEmpty()
                    .toMutableMap()

                val existing = currentCache[stockCode]
                val existingSequence = existing?.requestSequence ?: existing?.lastFetchedTimeMillis ?: 0L
                val incomingSequence = entry.requestSequence ?: entry.lastFetchedTimeMillis ?: 0L

                if (existing == null || incomingSequence > existingSequence) {
                    currentCache[stockCode] = entry
                    preferences[dividendInfoCacheKey] = json.encodeToString(currentCache)
                }

                val maxSeq = maxOf(existingSequence, incomingSequence)
                syncSequenceWithPersisted(maxSeq)
            }
        }
    }

    suspend fun setHomeDisplayMode(mode: String) {
        dataStoreInstance.edit {
            it[homeDisplayModeKey] = HomeDisplayMode.normalize(mode)
        }
    }

    suspend fun setHoldingsOrder(order: List<String>) {
        dataStoreInstance.edit {
            it[holdingsOrderKey] = order.joinToString("|")
        }
    }

    suspend fun setRealizedHoldingsOrder(order: List<String>) {
        dataStoreInstance.edit {
            it[realizedHoldingsOrderKey] = order.joinToString("|")
        }
    }

    suspend fun setPartialSalesAsRealized(enabled: Boolean) {
        dataStoreInstance.edit {
            it[partialSalesAsRealizedKey] = enabled
        }
    }

    suspend fun setExcludeDividendIncomeFromReturns(enabled: Boolean) {
        dataStoreInstance.edit {
            it[excludeDividendIncomeFromReturnsKey] = enabled
        }
    }

    suspend fun setLastUpdateHighlightsVersion(versionName: String) {
        dataStoreInstance.edit {
            it[lastUpdateHighlightsVersionKey] = versionName
        }
    }

    suspend fun setHoldingsReorderHintShown(shown: Boolean) {
        dataStoreInstance.edit {
            it[holdingsReorderHintShownKey] = shown
        }
    }

    suspend fun setHomeHoldingsSortMode(mode: String) {
        dataStoreInstance.edit {
            it[homeHoldingsSortModeKey] = mode
        }
    }

    suspend fun setHomeHoldingsSortPreference(
        mode: String,
        column: String,
        ascending: Boolean
    ) {
        dataStoreInstance.edit {
            it[homeHoldingsSortModeKey] = mode
            it[homeHoldingsSortColumnKey] = column
            it[homeHoldingsSortAscendingKey] = ascending
        }
    }

    suspend fun setLocalCsvRestoreFeeHintShown(shown: Boolean) {
        dataStoreInstance.edit {
            it[localCsvRestoreFeeHintShownKey] = shown
        }
    }

    suspend fun setShowTaiwanWeightedIndex(show: Boolean) {
        dataStoreInstance.edit {
            it[showTaiwanWeightedIndexKey] = show
        }
    }

    suspend fun setShowTaiwanPortfolioChart(show: Boolean) {
        dataStoreInstance.edit {
            it[showTaiwanPortfolioChartKey] = show
        }
    }

    suspend fun setHomeHistoryChartExpanded(expanded: Boolean) {
        dataStoreInstance.edit {
            it[homeHistoryChartExpandedKey] = expanded
        }
    }

    suspend fun setDetailHistoryChartExpanded(expanded: Boolean) {
        dataStoreInstance.edit {
            it[detailHistoryChartExpandedKey] = expanded
        }
    }

    suspend fun setCloudDataBackupUpdatedAt(timeMillis: Long) {
        dataStoreInstance.edit {
            it[cloudDataBackupUpdatedAtKey] = timeMillis
        }
    }

    suspend fun clearCloudBackupMetadata() {
        dataStoreInstance.edit {
            clearCloudBackupMetadata(it)
            // 刪除備份後，較早開始的雲端讀取或上傳不得重新填入已清除的紀錄。
            it[googleDriveAuthVersionKey] = (it[googleDriveAuthVersionKey] ?: 0L) + 1L
            it.remove(googleDriveAuthValidationTimeKey)
        }
    }

    private fun clearCloudBackupMetadata(preferences: MutablePreferences) {
        with(preferences) {
            remove(cloudDataBackupUpdatedAtKey)
            remove(autoCloudBackupLastAttemptAtKey)
            remove(autoCloudBackupLastSuccessAtKey)
            remove(autoCloudBackupLastLocalSuccessAtKey)
            remove(autoCloudBackupLastErrorKey)
        }
    }

    suspend fun clearGoogleDriveAccountAndBackupMetadata() {
        dataStoreInstance.edit {
            clearCloudBackupMetadata(it)
            it.remove(googleAccountEmailKey)
            it[googleDriveAuthStateKey] = GoogleDriveAuthState.NOT_SIGNED_IN.name
            it[googleDriveAuthVersionKey] = (it[googleDriveAuthVersionKey] ?: 0L) + 1L
            it.remove(googleDriveAuthValidationTimeKey)
        }
    }

    suspend fun setGoogleDriveAuthState(state: GoogleDriveAuthState) {
        dataStoreInstance.edit { preferences ->
            invalidateGoogleDriveAuthIfNeeded(preferences, preferences[googleAccountEmailKey], state)
            preferences[googleDriveAuthStateKey] = state.name
        }
    }

    private fun invalidateGoogleDriveAuthIfNeeded(
        preferences: MutablePreferences,
        email: String?,
        state: GoogleDriveAuthState
    ) {
        val accountChanged = preferences[googleAccountEmailKey] != email
        val previousState = parseGoogleDriveAuthState(preferences[googleDriveAuthStateKey], preferences[googleAccountEmailKey])
        val authorizationRevoked = state != previousState &&
            (state == GoogleDriveAuthState.NOT_SIGNED_IN || state == GoogleDriveAuthState.NEEDS_REAUTHORIZATION)
        // 一般驗證不取代正在執行的背景工作；帳戶切換或撤銷授權才使其失效。
        if (accountChanged || authorizationRevoked) {
            preferences[googleDriveAuthVersionKey] = (preferences[googleDriveAuthVersionKey] ?: 0L) + 1L
            preferences.remove(googleDriveAuthValidationTimeKey)
        }
    }

    suspend fun updateGoogleDriveAccountState(
        email: String?,
        state: GoogleDriveAuthState,
        lastError: String? = null
    ) {
        dataStoreInstance.edit { preferences ->
            invalidateGoogleDriveAuthIfNeeded(preferences, email, state)
            if (preferences[googleAccountEmailKey] != email) {
                preferences.remove(autoCloudBackupLastSuccessAtKey)
                preferences.remove(autoCloudBackupLastLocalSuccessAtKey)
                preferences.remove(autoCloudBackupLastAttemptAtKey)
                preferences.remove(autoCloudBackupLastErrorKey)
            }
            if (email.isNullOrBlank()) {
                preferences.remove(googleAccountEmailKey)
                preferences[googleDriveAuthStateKey] = GoogleDriveAuthState.NOT_SIGNED_IN.name
            } else {
                preferences[googleAccountEmailKey] = email
                preferences[googleDriveAuthStateKey] = state.name
            }
            if (lastError != null) {
                preferences[autoCloudBackupLastErrorKey] = lastError
            }
        }
    }

    suspend fun beginGoogleDriveAuthValidationIfMatching(
        expectedEmail: String?,
        expectedVersion: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Long? {
        var attemptTime: Long? = null
        dataStoreInstance.edit { preferences ->
            if (preferences[googleAccountEmailKey] == expectedEmail &&
                (preferences[googleDriveAuthVersionKey] ?: 0L) == expectedVersion
            ) {
                // 同毫秒開始或時鐘回撥時，仍依 DataStore 的原子寫入順序分配序號。
                val nextAttempt = maxOf(nowMillis, (preferences[googleDriveAuthValidationTimeKey] ?: 0L) + 1L)
                preferences[googleDriveAuthValidationTimeKey] = nextAttempt
                attemptTime = nextAttempt
            }
        }
        return attemptTime
    }

    suspend fun setGoogleDriveAuthStateIfMatching(
        expectedEmail: String?,
        expectedVersion: Long,
        state: GoogleDriveAuthState,
        lastError: String? = null,
        clearLastError: Boolean = false,
        attemptTime: Long = 0L,
        backupSuccessAt: Long? = null,
        localBackupSuccessAt: Long? = null
    ): Boolean {
        var updated = false
        dataStoreInstance.edit { preferences ->
            val currentEmail = preferences[googleAccountEmailKey]
            val currentVersion = preferences[googleDriveAuthVersionKey] ?: 0L
            val lastValidationTime = preferences[googleDriveAuthValidationTimeKey] ?: 0L
            if (GoogleDriveAuthResolutionHelper.isAuthVersionMatching(
                    currentEmail, currentVersion, expectedEmail, expectedVersion,
                    attemptTime, lastValidationTime
                )
            ) {
                preferences[googleDriveAuthStateKey] = state.name
                if (attemptTime > 0L) {
                    preferences[googleDriveAuthValidationTimeKey] = attemptTime
                }
                if (clearLastError) {
                    preferences.remove(autoCloudBackupLastErrorKey)
                } else if (lastError != null) {
                    preferences[autoCloudBackupLastErrorKey] = lastError
                }
                if (backupSuccessAt != null) {
                    preferences[autoCloudBackupLastSuccessAtKey] = backupSuccessAt
                }
                if (localBackupSuccessAt != null) {
                    preferences[autoCloudBackupLastLocalSuccessAtKey] = localBackupSuccessAt
                }
                updated = true
            }
        }
        return updated
    }

    suspend fun setGoogleAccountEmail(email: String?) {
        dataStoreInstance.edit { preferences ->
            if (preferences[googleAccountEmailKey] != email) {
                preferences.remove(autoCloudBackupLastSuccessAtKey)
                preferences.remove(autoCloudBackupLastLocalSuccessAtKey)
                preferences.remove(autoCloudBackupLastAttemptAtKey)
                preferences.remove(autoCloudBackupLastErrorKey)
            }
            if (email.isNullOrBlank()) preferences.remove(googleAccountEmailKey)
            else preferences[googleAccountEmailKey] = email
            preferences[googleDriveAuthVersionKey] = (preferences[googleDriveAuthVersionKey] ?: 0L) + 1L
            preferences.remove(googleDriveAuthValidationTimeKey)
        }
    }

    suspend fun setAutoCloudBackupEnabled(enabled: Boolean) {
        dataStoreInstance.edit { it[autoCloudBackupEnabledKey] = enabled }
    }

    suspend fun setAutoCloudBackupIntervalDays(days: Int) {
        require(days in setOf(0, 1, 3, 7))
        dataStoreInstance.edit { it[autoCloudBackupIntervalDaysKey] = days }
    }

    suspend fun claimAutomaticCloudBackupAttempt(
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        retryCooldownMillis: Long = 5 * 60 * 1000L
    ): Boolean {
        var claimed = false
        dataStoreInstance.edit { preferences ->
            val enabled = preferences[autoCloudBackupEnabledKey] ?: false
            val interval = preferences[autoCloudBackupIntervalDaysKey]
                ?.takeIf { it in setOf(0, 1, 3, 7) } ?: 1
            val lastAttempt = preferences[autoCloudBackupLastAttemptAtKey] ?: 0L
            val lastSuccess = preferences[autoCloudBackupLastLocalSuccessAtKey]
                ?: preferences[autoCloudBackupLastSuccessAtKey] ?: 0L
            val hasRecordedFailure = preferences[autoCloudBackupLastErrorKey] != null

            val due = isAutomaticCloudBackupDue(
                enabled = enabled,
                intervalDays = interval,
                lastAttempt = lastAttempt,
                lastSuccess = lastSuccess,
                hasRecordedFailure = hasRecordedFailure,
                nowMillis = nowMillis,
                zoneId = zoneId,
                retryCooldownMillis = retryCooldownMillis
            )

            if (due) {
                preferences[autoCloudBackupLastAttemptAtKey] = nowMillis
                claimed = true
            }
        }
        return claimed
    }

    suspend fun setAutoCloudBackupLastErrorIfMatching(
        expectedEmail: String?,
        expectedVersion: Long,
        attemptTime: Long,
        message: String,
        failedAt: Long
    ): Boolean {
        require(attemptTime > 0L)
        var updated = false
        dataStoreInstance.edit { preferences ->
            if (GoogleDriveAuthResolutionHelper.isAuthVersionMatching(
                    currentEmail = preferences[googleAccountEmailKey],
                    currentVersion = preferences[googleDriveAuthVersionKey] ?: 0L,
                    expectedEmail = expectedEmail,
                    expectedVersion = expectedVersion,
                    attemptTime = attemptTime,
                    lastValidationTime = preferences[googleDriveAuthValidationTimeKey] ?: 0L
                )
            ) {
                preferences[autoCloudBackupLastErrorKey] = message.take(300)
                preferences[autoCloudBackupLastAttemptAtKey] = failedAt
                updated = true
            }
        }
        return updated
    }

    suspend fun setAutoCloudBackupModifiedTimeIfMatching(
        expectedEmail: String,
        expectedVersion: Long,
        expectedLastSuccessAt: Long?,
        modifiedTime: Long?,
        expectedLastLocalSuccessAt: Long? = null
    ): Boolean {
        require(modifiedTime == null || modifiedTime > 0L)
        var updated = false
        dataStoreInstance.edit { preferences ->
            // 只同步雲端檔案時間；讀取期間若有新備份完成，保留該次成功結果。
            if (preferences[googleAccountEmailKey] == expectedEmail &&
                (preferences[googleDriveAuthVersionKey] ?: 0L) == expectedVersion &&
                preferences[autoCloudBackupLastSuccessAtKey] == expectedLastSuccessAt &&
                preferences[autoCloudBackupLastLocalSuccessAtKey] == expectedLastLocalSuccessAt
            ) {
                if (modifiedTime == null) {
                    preferences.remove(autoCloudBackupLastSuccessAtKey)
                    preferences.remove(autoCloudBackupLastLocalSuccessAtKey)
                } else {
                    preferences[autoCloudBackupLastSuccessAtKey] = modifiedTime
                }
                updated = true
            }
        }
        return updated
    }

    suspend fun setAutoCloudBackupLastError(message: String?, attemptTimeMillis: Long? = null) {
        dataStoreInstance.edit { preferences ->
            if (message.isNullOrBlank()) {
                preferences.remove(autoCloudBackupLastErrorKey)
            } else {
                preferences[autoCloudBackupLastErrorKey] = message.take(300)
                preferences[autoCloudBackupLastAttemptAtKey] = attemptTimeMillis ?: System.currentTimeMillis()
            }
        }
    }

    suspend fun setActiveAccountId(accountId: Int) {
        dataStoreInstance.edit {
            it[activeAccountIdKey] = accountId
        }
    }

    suspend fun setMarginFeatureEnabled(enabled: Boolean) {
        dataStoreInstance.edit { it[marginFeatureEnabledKey] = enabled }
    }

    suspend fun setMarginDayCount(dayCount: Int) {
        dataStoreInstance.edit { it[marginDayCountKey] = if (dayCount == 360) 360 else 365 }
    }

    suspend fun setDefaultMarginAnnualRate(rate: Double) {
        if (!rate.isFinite() || rate < 0.0) return
        dataStoreInstance.edit { it[defaultMarginAnnualRateKey] = rate }
    }

    suspend fun setDefaultShortBorrowAnnualRate(rate: Double) {
        if (!rate.isFinite() || rate < 0.0) return
        dataStoreInstance.edit { it[defaultShortBorrowAnnualRateKey] = rate }
    }

    suspend fun setBankDeposits(deposits: List<BankDeposit>) {
        dataStoreInstance.edit {
            it[bankDepositsKey] = Json.encodeToString(deposits)
        }
    }

    suspend fun setLoans(loans: List<Loan>) {
        dataStoreInstance.edit {
            it[loansKey] = Json.encodeToString(loans)
        }
    }

    suspend fun replaceAssetBalances(balances: AssetBalances) {
        AssetBalanceBackupCodec.validate(balances)
        dataStoreInstance.edit {
            it[bankDepositsKey] = Json.encodeToString(balances.bankDeposits)
            it[loansKey] = Json.encodeToString(balances.loans)
        }
    }

    suspend fun readAssetBalancesForBackup(): AssetBalances {
        val preferences = dataStoreInstance.data.first()
        // Export must fail on damaged data rather than create a seemingly valid empty backup.
        return AssetBalances(
            preferences[bankDepositsKey]?.let { Json.decodeFromString<List<BankDeposit>>(it) }.orEmpty(),
            preferences[loansKey]?.let { Json.decodeFromString<List<Loan>>(it) }.orEmpty()
        )
    }

    suspend fun updateAssetBalances(transform: (AssetBalances) -> AssetBalances) {
        dataStoreInstance.edit { preferences ->
            val current = AssetBalances(
                preferences[bankDepositsKey]?.let { Json.decodeFromString<List<BankDeposit>>(it) }.orEmpty(),
                preferences[loansKey]?.let { Json.decodeFromString<List<Loan>>(it) }.orEmpty()
            )
            val updated = transform(current)
            AssetBalanceBackupCodec.validate(updated)
            preferences[bankDepositsKey] = Json.encodeToString(updated.bankDeposits)
            preferences[loansKey] = Json.encodeToString(updated.loans)
        }
    }

    suspend fun enableAppLock(pin: String) {
        val pinHash = withContext(Dispatchers.Default) { hashAppLockPin(pin) }
        dataStoreInstance.edit {
            it[appLockPinSaltKey] = pinHash.salt
            it[appLockPinHashKey] = pinHash.hash
            it[appLockBiometricEnabledKey] = false
            it[appLockEnabledKey] = true
        }
    }

    suspend fun verifyAppLockPin(pin: String): Boolean {
        val preferences = dataStoreInstance.data.first()
        val salt = preferences[appLockPinSaltKey] ?: return false
        val hash = preferences[appLockPinHashKey] ?: return false
        return withContext(Dispatchers.Default) { verifyAppLockPin(pin, salt, hash) }
    }

    suspend fun changeAppLockPin(currentPin: String, newPin: String): Boolean {
        if (!verifyAppLockPin(currentPin) || !isValidAppLockPin(newPin)) return false
        val pinHash = withContext(Dispatchers.Default) { hashAppLockPin(newPin) }
        dataStoreInstance.edit {
            it[appLockPinSaltKey] = pinHash.salt
            it[appLockPinHashKey] = pinHash.hash
        }
        return true
    }

    suspend fun disableAppLock(pin: String): Boolean {
        if (!verifyAppLockPin(pin)) return false
        dataStoreInstance.edit {
            it.remove(appLockEnabledKey)
            it.remove(appLockPinSaltKey)
            it.remove(appLockPinHashKey)
            it.remove(appLockBiometricEnabledKey)
        }
        return true
    }

    suspend fun setAppLockBiometricEnabled(enabled: Boolean) {
        dataStoreInstance.edit {
            if (it[appLockEnabledKey] == true) {
                it[appLockBiometricEnabledKey] = enabled
            } else {
                it.remove(appLockBiometricEnabledKey)
            }
        }
    }

}

internal const val DEFAULT_MARGIN_ANNUAL_RATE = 6.45
internal const val DEFAULT_SHORT_BORROW_ANNUAL_RATE = 3.5
