package com.belltimer.app

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import java.util.Locale

/**
 * The app language chosen in the settings. An empty tag follows the system
 * (including the per-app language of Android 13+). The choice is applied by
 * wrapping the activity context, so it works on every supported Android
 * version without AppCompat.
 */
object AppLocale {

    /** Language tags offered in the settings; "" = system. */
    val SUPPORTED = listOf("", "ru", "be", "en")

    private const val PREFS_NAME = "belltimer_settings"
    private const val KEY_LANGUAGE = "language"

    fun tag(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "").orEmpty()
            .takeIf { it in SUPPORTED }
            .orEmpty()

    fun save(context: Context, tag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_LANGUAGE, tag) }
    }

    /** [base] with the chosen language, or [base] itself for the system one. */
    fun wrap(base: Context): Context {
        val tag = tag(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
