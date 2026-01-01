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

    private val refreshIntervalKey = intPreferencesKey("refresh_interval")
    private val yahooFetchIntervalKey = intPreferencesKey("yahoo_fetch_interval")
    private val lastStockListUpdateTimeKey = longPreferencesKey("last_stock_list_update_time")

    private val feeDiscountKey = doublePreferencesKey("fee_discount")
    private val minFeeRegularKey = intPreferencesKey("min_fee_regular")
    private val minFeeOddLotKey = intPreferencesKey("min_fee_odd_lot")
    private val dividendFeeKey = intPreferencesKey("dividend_fee")
    private val preDeductSellFeesKey = booleanPreferencesKey("pre_deduct_sell_fees")
    private val realtimeStockInfoCacheKey = stringPreferencesKey("realtime_stock_info_cache")
    private val themeKey = stringPreferencesKey("theme")
    private val stockDataSourceKey = stringPreferencesKey("stock_data_source")
    private val notifyFallbackRepeatedlyKey = booleanPreferencesKey("notify_fallback_repeatedly")

    val refreshIntervalFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[refreshIntervalKey] ?: 5
        }

    val yahooFetchIntervalFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[yahooFetchIntervalKey] ?: 10
        }

    val lastStockListUpdateTimeFlow: Flow<Long?> = context.dataStore.data
        .map { preferences ->
            preferences[lastStockListUpdateTimeKey]
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

    val stockDataSourceFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[stockDataSourceKey] ?: "TWSE"
        }

    val notifyFallbackRepeatedlyFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[notifyFallbackRepeatedlyKey] ?: false
        }

    suspend fun setRefreshInterval(interval: Int) {
        context.dataStore.edit {
            it[refreshIntervalKey] = interval
        }
    }

    suspend fun setYahooFetchInterval(interval: Int) {
        context.dataStore.edit {
            it[yahooFetchIntervalKey] = interval
        }
    }

    suspend fun setLastStockListUpdateTime(time: Long) {
        context.dataStore.edit {
            it[lastStockListUpdateTimeKey] = time
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

    suspend fun setStockDataSource(source: String) {
        context.dataStore.edit {
            it[stockDataSourceKey] = source
        }
    }

    suspend fun setNotifyFallbackRepeatedly(shouldNotifyRepeatedly: Boolean) {
        context.dataStore.edit {
            it[notifyFallbackRepeatedlyKey] = shouldNotifyRepeatedly
        }
    }
}