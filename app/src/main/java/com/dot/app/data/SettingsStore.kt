package com.dot.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dot_settings")

data class DotSettings(
    val confirmationsEnabled: Boolean = true,
    val aiFallbackEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val cacheSessionMinutes: Int = 30,
)

/**
 * DataStore-backed settings. Reading a corrupt preferences file must not crash
 * the app, so IO failures fall back to defaults instead of propagating.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<DotSettings> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            DotSettings(
                confirmationsEnabled = prefs[CONFIRMATIONS] ?: true,
                aiFallbackEnabled = prefs[AI_FALLBACK] ?: false,
                notificationsEnabled = prefs[NOTIFICATIONS] ?: true,
                cacheSessionMinutes = prefs[CACHE_SESSION_MINUTES] ?: 30,
            )
        }

    suspend fun setConfirmationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CONFIRMATIONS] = enabled }
    }

    suspend fun setAiFallbackEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AI_FALLBACK] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[NOTIFICATIONS] = enabled }
    }

    private companion object {
        val CONFIRMATIONS = booleanPreferencesKey("confirmations_enabled")
        val AI_FALLBACK = booleanPreferencesKey("ai_fallback_enabled")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val CACHE_SESSION_MINUTES = androidx.datastore.preferences.core.intPreferencesKey(
            "cache_session_minutes",
        )
    }
}
