package com.tinnitusrelief.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "settings")

data class SavedSettings(
    val soundId: String?,
    val volume: Float,
    val timerMinutes: Int,
)

/** Local-only persisted preferences (DataStore). */
class SettingsStore(private val context: Context) {

    suspend fun load(): SavedSettings {
        val p = context.dataStore.data.first()
        return SavedSettings(
            soundId = p[SOUND],
            volume = p[VOLUME] ?: DEFAULT_VOLUME,
            timerMinutes = p[TIMER] ?: 0,
        )
    }

    suspend fun saveSound(id: String) = context.dataStore.edit { it[SOUND] = id }
    suspend fun saveVolume(v: Float) = context.dataStore.edit { it[VOLUME] = v }
    suspend fun saveTimer(m: Int) = context.dataStore.edit { it[TIMER] = m }

    companion object {
        /** Conservative first-run level: about -26 dB below the source material. */
        const val DEFAULT_VOLUME = 0.45f
        private val SOUND = stringPreferencesKey("sound_id")
        private val VOLUME = floatPreferencesKey("volume")
        private val TIMER = intPreferencesKey("timer_minutes")
    }
}
