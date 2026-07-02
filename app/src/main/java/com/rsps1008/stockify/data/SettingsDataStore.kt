package com.rsps1008.stockify.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(val context: Context) {

    private val fetchIntervalKey = intPreferencesKey("refresh_interval")
    private val lastStockListUpdateTimeKey = longPreferencesKey("last_stock_list_update_time")
    private val lastUsStockListUpdateTimeKey = longPreferencesKey("last_us_stock_list_update_time")
    private val feeDiscountKey = doublePreferencesKey("fee_discount")
    private val minFeeRegularKey = intPreferencesKey("min_fee_regular")
    private val minFeeOddLotKey = intPreferencesKey("min_fee_odd_lot")
    private val dividendFeeKey = intPreferencesKey("dividend_fee")
    private val preDeductSellFeesKey = booleanPreferencesKey("pre_deduct_sell_fees")
    private val useCumulativeReturnRateKey = booleanPreferencesKey("use_cumulative_return_rate")
    private val returnRateModeKey = stringPreferencesKey("return_rate_mode")
    private val realtimeStockInfoCacheKey = stringPreferencesKey("realtime_stock_info_cache")
    private val themeKey = stringPreferencesKey("theme")
    private val textSizeModeKey = stringPreferencesKey("text_size_mode")
    private val stockDataSourceKey = stringPreferencesKey("stock_data_source")
    private val usStockDataSourceKey = stringPreferencesKey("us_stock_data_source")
    private val notifyFallbackRepeatedlyKey = booleanPreferencesKey("notify_fallback_repeatedly")
    private val taxRateNormalListedStockKey = doublePreferencesKey("tax_rate_normal_listed_stock")
    private val taxRateDomesticStockEtfKey = doublePreferencesKey("tax_rate_domestic_stock_etf")
    private val taxRateBondEtfKey = doublePreferencesKey("tax_rate_bond_etf")
    private val taxRateDayTradingKey = doublePreferencesKey("tax_rate_day_trading")
    private val skipPdfImportTutorialKey = booleanPreferencesKey("skip_pdf_import_tutorial")
    private val usdToTwdRateKey = doublePreferencesKey("usd_to_twd_rate")
    private val usdToTwdRateUpdatedAtKey = longPreferencesKey("usd_to_twd_rate_updated_at")
    private val taiwanWeightedIndexCacheKey = stringPreferencesKey("taiwan_weighted_index_cache")
    private val homeDisplayModeKey = stringPreferencesKey("home_display_mode")
    private val finnhubApiKeyKey = stringPreferencesKey("finnhub_api_key")
    private val holdingsOrderKey = stringPreferencesKey("holdings_order")
    private val realizedHoldingsOrderKey = stringPreferencesKey("realized_holdings_order")
    private val holdingsReorderHintShownKey = booleanPreferencesKey("holdings_reorder_hint_shown")
    private val homeHoldingsSortModeKey = stringPreferencesKey("home_holdings_sort_mode")
    private val homeHoldingsSortColumnKey = stringPreferencesKey("home_holdings_sort_column")
    private val homeHoldingsSortAscendingKey = booleanPreferencesKey("home_holdings_sort_ascending")
    private val localCsvRestoreFeeHintShownKey = booleanPreferencesKey("local_csv_restore_fee_hint_shown")
    private val calculationRoundingModeKey = stringPreferencesKey("calculation_rounding_mode")
    private val showTaiwanWeightedIndexKey = booleanPreferencesKey("show_taiwan_weighted_index")

    val fetchIntervalFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[fetchIntervalKey] ?: 10
        }

    val lastStockListUpdateTimeFlow: Flow<Long?> = context.dataStore.data
        .map { preferences ->
            preferences[lastStockListUpdateTimeKey]
        }

    val lastUsStockListUpdateTimeFlow: Flow<Long?> = context.dataStore.data
        .map { preferences ->
            preferences[lastUsStockListUpdateTimeKey]
        }

    val feeDiscountFlow: Flow<Double> = context.dataStore.data
        .map { preferences ->
            preferences[feeDiscountKey] ?: 0.28
        }

    val minFeeRegularFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[minFeeRegularKey] ?: 1
        }

    val minFeeOddLotFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[minFeeOddLotKey] ?: 1
        }
    val dividendFeeFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[dividendFeeKey] ?: 10
        }
    
    val preDeductSellFeesFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[preDeductSellFeesKey] ?: true
        }

    val returnRateModeFlow: Flow<ReturnRateMode> = context.dataStore.data
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

    val calculationRoundingModeFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            CalculationRoundingMode.normalize(preferences[calculationRoundingModeKey])
        }

    val realtimeStockInfoCacheFlow: Flow<Map<String, RealtimeStockInfo>> = context.dataStore.data
        .map { preferences ->
            preferences[realtimeStockInfoCacheKey]?.let {
                Json.decodeFromString<Map<String, RealtimeStockInfo>>(it)
            } ?: emptyMap()
        }
    
    val themeFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[themeKey] ?: "System"
        }

    val textSizeModeFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[textSizeModeKey] ?: TextSizeMode.DEFAULT
        }

    val stockDataSourceFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[stockDataSourceKey] ?: "TWSE"
        }

    val usStockDataSourceFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[usStockDataSourceKey] ?: "Nasdaq"
        }

    val notifyFallbackRepeatedlyFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[notifyFallbackRepeatedlyKey] ?: false
        }

    val taxRateNormalListedStockFlow: Flow<Double> = context.dataStore.data
        .map { preferences ->
            preferences[taxRateNormalListedStockKey] ?: 0.003
        }

    val taxRateDomesticStockEtfFlow: Flow<Double> = context.dataStore.data
        .map { preferences ->
            preferences[taxRateDomesticStockEtfKey] ?: 0.001
        }

    val taxRateBondEtfFlow: Flow<Double> = context.dataStore.data
        .map { preferences ->
            preferences[taxRateBondEtfKey] ?: 0.0
        }

    val taxRateDayTradingFlow: Flow<Double> = context.dataStore.data
        .map { preferences ->
            preferences[taxRateDayTradingKey] ?: 0.0015
        }

    val skipPdfImportTutorialFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[skipPdfImportTutorialKey] ?: false
        }

    val usdToTwdRateFlow: Flow<Double> = context.dataStore.data
        .map { preferences ->
            preferences[usdToTwdRateKey] ?: 32.0
        }

    val usdToTwdRateUpdatedAtFlow: Flow<Long?> = context.dataStore.data
        .map { preferences ->
            preferences[usdToTwdRateUpdatedAtKey]
        }

    val taiwanWeightedIndexCacheFlow: Flow<TaiwanWeightedIndexInfo?> = context.dataStore.data
        .map { preferences ->
            preferences[taiwanWeightedIndexCacheKey]?.let {
                Json.decodeFromString<TaiwanWeightedIndexInfo>(it)
            }
        }

    val homeDisplayModeFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[homeDisplayModeKey] ?: HomeDisplayMode.COMBINED
        }

    val finnhubApiKeyFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[finnhubApiKeyKey] ?: ""
        }

    val holdingsOrderFlow: Flow<List<String>> = context.dataStore.data
        .map { preferences ->
            preferences[holdingsOrderKey]
                ?.split("|")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }

    val realizedHoldingsOrderFlow: Flow<List<String>> = context.dataStore.data
        .map { preferences ->
            preferences[realizedHoldingsOrderKey]
                ?.split("|")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }

    val holdingsReorderHintShownFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[holdingsReorderHintShownKey] ?: false
        }

    val homeHoldingsSortModeFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[homeHoldingsSortModeKey] ?: "MANUAL"
        }

    val homeHoldingsSortColumnFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[homeHoldingsSortColumnKey] ?: "NONE"
        }

    val homeHoldingsSortAscendingFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[homeHoldingsSortAscendingKey] ?: true
        }

    val localCsvRestoreFeeHintShownFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[localCsvRestoreFeeHintShownKey] ?: false
        }

    val showTaiwanWeightedIndexFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[showTaiwanWeightedIndexKey] ?: true
        }

    suspend fun setFetchInterval(interval: Int) {
        context.dataStore.edit {
            it[fetchIntervalKey] = interval
        }
    }

    suspend fun setLastStockListUpdateTime(time: Long) {
        context.dataStore.edit {
            it[lastStockListUpdateTimeKey] = time
        }
    }

    suspend fun setLastUsStockListUpdateTime(time: Long) {
        context.dataStore.edit {
            it[lastUsStockListUpdateTimeKey] = time
        }
    }

    suspend fun setFeeDiscount(discount: Double) {
        context.dataStore.edit {
            it[feeDiscountKey] = discount
        }
    }

    suspend fun setMinFeeRegular(fee: Int) {
        context.dataStore.edit {
            it[minFeeRegularKey] = fee
        }
    }

    suspend fun setMinFeeOddLot(fee: Int) {
        context.dataStore.edit {
            it[minFeeOddLotKey] = fee
        }
    }
    
    suspend fun setDividendFee(fee: Int) {
        context.dataStore.edit {
            it[dividendFeeKey] = fee
        }
    }

    suspend fun setPreDeductSellFees(preDeduct: Boolean) {
        context.dataStore.edit {
            it[preDeductSellFeesKey] = preDeduct
        }
    }

    suspend fun setReturnRateMode(mode: ReturnRateMode) {
        context.dataStore.edit {
            it[returnRateModeKey] = mode.key
            it[useCumulativeReturnRateKey] = mode == ReturnRateMode.CUMULATIVE_INVESTMENT
        }
    }

    suspend fun setUseCumulativeReturnRate(useCumulative: Boolean) {
        setReturnRateMode(if (useCumulative) ReturnRateMode.CUMULATIVE_INVESTMENT else ReturnRateMode.REMAINING_POSITION)
    }

    suspend fun setCalculationRoundingMode(mode: String) {
        context.dataStore.edit {
            it[calculationRoundingModeKey] = CalculationRoundingMode.normalize(mode)
        }
    }

    suspend fun setRealtimeStockInfoCache(cache: Map<String, RealtimeStockInfo>) {
        context.dataStore.edit {
            it[realtimeStockInfoCacheKey] = Json.encodeToString(cache)
        }
    }

    suspend fun clearRealtimeStockInfoCache() {
        context.dataStore.edit {
            it.remove(realtimeStockInfoCacheKey)
        }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit {
            it[themeKey] = theme
        }
    }

    suspend fun setTextSizeMode(mode: String) {
        context.dataStore.edit {
            it[textSizeModeKey] = TextSizeMode.normalize(mode)
        }
    }

    suspend fun setStockDataSource(source: String) {
        context.dataStore.edit {
            it[stockDataSourceKey] = source
        }
    }

    suspend fun setUsStockDataSource(source: String) {
        context.dataStore.edit {
            it[usStockDataSourceKey] = source
        }
    }

    suspend fun setNotifyFallbackRepeatedly(shouldNotifyRepeatedly: Boolean) {
        context.dataStore.edit {
            it[notifyFallbackRepeatedlyKey] = shouldNotifyRepeatedly
        }
    }

    suspend fun setTaxRateNormalListedStock(rate: Double) {
        context.dataStore.edit {
            it[taxRateNormalListedStockKey] = rate
        }
    }

    suspend fun setTaxRateDomesticStockEtf(rate: Double) {
        context.dataStore.edit {
            it[taxRateDomesticStockEtfKey] = rate
        }
    }

    suspend fun setTaxRateBondEtf(rate: Double) {
        context.dataStore.edit {
            it[taxRateBondEtfKey] = rate
        }
    }

    suspend fun setTaxRateDayTrading(rate: Double) {
        context.dataStore.edit {
            it[taxRateDayTradingKey] = rate
        }
    }

    suspend fun setSkipPdfImportTutorial(skip: Boolean) {
        context.dataStore.edit {
            it[skipPdfImportTutorialKey] = skip
        }
    }

    suspend fun setUsdToTwdRate(rate: Double, updatedAt: Long = System.currentTimeMillis()) {
        context.dataStore.edit {
            it[usdToTwdRateKey] = rate
            it[usdToTwdRateUpdatedAtKey] = updatedAt
        }
    }

    suspend fun setTaiwanWeightedIndexCache(info: TaiwanWeightedIndexInfo) {
        context.dataStore.edit {
            it[taiwanWeightedIndexCacheKey] = Json.encodeToString(info)
        }
    }

    suspend fun setHomeDisplayMode(mode: String) {
        context.dataStore.edit {
            it[homeDisplayModeKey] = HomeDisplayMode.normalize(mode)
        }
    }

    suspend fun setFinnhubApiKey(apiKey: String) {
        context.dataStore.edit {
            it[finnhubApiKeyKey] = apiKey.trim()
        }
    }

    suspend fun setHoldingsOrder(order: List<String>) {
        context.dataStore.edit {
            it[holdingsOrderKey] = order.joinToString("|")
        }
    }

    suspend fun setRealizedHoldingsOrder(order: List<String>) {
        context.dataStore.edit {
            it[realizedHoldingsOrderKey] = order.joinToString("|")
        }
    }

    suspend fun setHoldingsReorderHintShown(shown: Boolean) {
        context.dataStore.edit {
            it[holdingsReorderHintShownKey] = shown
        }
    }

    suspend fun setHomeHoldingsSortMode(mode: String) {
        context.dataStore.edit {
            it[homeHoldingsSortModeKey] = mode
        }
    }

    suspend fun setHomeHoldingsSortPreference(
        mode: String,
        column: String,
        ascending: Boolean
    ) {
        context.dataStore.edit {
            it[homeHoldingsSortModeKey] = mode
            it[homeHoldingsSortColumnKey] = column
            it[homeHoldingsSortAscendingKey] = ascending
        }
    }

    suspend fun setLocalCsvRestoreFeeHintShown(shown: Boolean) {
        context.dataStore.edit {
            it[localCsvRestoreFeeHintShownKey] = shown
        }
    }

    suspend fun setShowTaiwanWeightedIndex(show: Boolean) {
        context.dataStore.edit {
            it[showTaiwanWeightedIndexKey] = show
        }
    }
}
