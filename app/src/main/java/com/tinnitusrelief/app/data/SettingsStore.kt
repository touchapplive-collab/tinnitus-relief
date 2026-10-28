package com.tinnitusrelief.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Local-only persisted preferences (DataStore). Nothing leaves the device. */
class SettingsStore(private val context: Context) {

    suspend fun snapshot(): Preferences = context.dataStore.data.first()

    suspend fun putString(key: Preferences.Key<String>, v: String) = context.dataStore.edit { it[key] = v }
    suspend fun putFloat(key: Preferences.Key<Float>, v: Float) = context.dataStore.edit { it[key] = v }
    suspend fun putInt(key: Preferences.Key<Int>, v: Int) = context.dataStore.edit { it[key] = v }
    suspend fun putBoolean(key: Preferences.Key<Boolean>, v: Boolean) = context.dataStore.edit { it[key] = v }

    companion object {
        /** Conservative first-run level: about −26 dB below the source material. */
        const val DEFAULT_VOLUME = 0.45f

        val MIX = stringPreferencesKey("mix")
        val PRESETS = stringPreferencesKey("presets")
        val VOLUME = floatPreferencesKey("volume")
        val TIMER = intPreferencesKey("timer_minutes")
        val LAB = stringPreferencesKey("lab_settings")
        val LAB_ACK = booleanPreferencesKey("lab_acknowledged")
        val PITCH = floatPreferencesKey("pitch_hz")
    }
}
