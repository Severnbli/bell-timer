package com.schoolbell.app.data

import android.content.Context
import androidx.core.content.edit

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val rawUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty()
        return AppSettings(
            baseUrl = rawUrl.ifBlank { DEFAULT_BASE_URL },
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            ringDefault = prefs.getInt(KEY_RING_DEFAULT, DEFAULT_RING_SECONDS)
                .coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit {
            putString(KEY_BASE_URL, settings.baseUrl)
            putString(KEY_TOKEN, settings.token)
            putInt(KEY_RING_DEFAULT, settings.ringDefault)
        }
    }

    private companion object {
        const val PREFS_NAME = "schoolbell_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
        const val KEY_RING_DEFAULT = "ring_default"
    }
}
