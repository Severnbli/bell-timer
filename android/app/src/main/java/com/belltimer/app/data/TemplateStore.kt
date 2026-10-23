package com.belltimer.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The user's own lesson templates, kept on the phone. */
class TemplateStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<LessonTemplate> {
        val raw = prefs.getString(KEY_TEMPLATES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<LessonTemplate>>(raw) }
            .getOrDefault(emptyList())
            .filter { !it.builtIn }
    }

    fun save(templates: List<LessonTemplate>) {
        prefs.edit { putString(KEY_TEMPLATES, json.encodeToString(templates.filter { !it.builtIn })) }
    }

    private companion object {
        const val PREFS_NAME = "belltimer_templates"
        const val KEY_TEMPLATES = "templates"
    }
}
