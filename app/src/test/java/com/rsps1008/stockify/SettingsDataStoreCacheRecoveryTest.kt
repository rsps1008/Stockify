package com.rsps1008.stockify

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.rsps1008.stockify.data.RealtimeStockInfo
import com.rsps1008.stockify.data.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsDataStoreCacheRecoveryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun malformedPersistedJsonUsesEmptyRealtimeCacheAndNullIndex() = runBlocking {
        val testFile = File(tempFolder.root, "settings.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { testFile })
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("realtime_stock_info_cache")] = "{not-json"
            preferences[stringPreferencesKey("taiwan_weighted_index_cache")] = "[not-json"
        }

        val settingsDataStore = SettingsDataStore(dataStore)

        assertEquals(
            emptyMap<String, RealtimeStockInfo>(),
            settingsDataStore.realtimeStockInfoCacheFlow.first()
        )
        assertNull(settingsDataStore.taiwanWeightedIndexCacheFlow.first())

        scope.cancel()
    }

    @Test
    fun partialSalesAsRealizedDefaultsToOffAndPersistsExplicitChoice() = runBlocking {
        val testFile = File(tempFolder.root, "partial-sales.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { testFile })
        val settingsDataStore = SettingsDataStore(dataStore)

        assertFalse(settingsDataStore.partialSalesAsRealizedFlow.first())
        settingsDataStore.setPartialSalesAsRealized(true)
        assertEquals(true, settingsDataStore.partialSalesAsRealizedFlow.first())

        scope.cancel()
    }

    @Test
    fun excludeDividendIncomeFromReturnsDefaultsToOffAndPersistsExplicitChoice() = runBlocking {
        val testFile = File(tempFolder.root, "exclude-dividends.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { testFile })
        val settingsDataStore = SettingsDataStore(dataStore)

        assertFalse(settingsDataStore.excludeDividendIncomeFromReturnsFlow.first())
        settingsDataStore.setExcludeDividendIncomeFromReturns(true)
        assertEquals(true, settingsDataStore.excludeDividendIncomeFromReturnsFlow.first())

        scope.cancel()
    }

    @Test
    fun updateHighlightsVersionStartsEmptyAndPersistsDismissedVersion() = runBlocking {
        val testFile = File(tempFolder.root, "update-highlights.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { testFile })
        val settingsDataStore = SettingsDataStore(dataStore)

        assertNull(settingsDataStore.lastUpdateHighlightsVersionFlow.first())
        settingsDataStore.setLastUpdateHighlightsVersion("1.6.8")
        assertEquals("1.6.8", settingsDataStore.lastUpdateHighlightsVersionFlow.first())

        scope.cancel()
    }
}
