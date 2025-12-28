package com.rsps1008.stockify.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(val context: Context) {

    private val refreshIntervalKey = intPreferencesKey("refresh_interval")

    val refreshIntervalFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[refreshIntervalKey] ?: 5
        }

    suspend fun setRefreshInterval(interval: Int) {
        context.dataStore.edit {
            it[refreshIntervalKey] = interval
        }
    }
}