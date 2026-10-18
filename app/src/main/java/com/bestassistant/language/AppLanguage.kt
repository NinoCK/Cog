package com.bestassistant.language

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * The assistant listens, answers and shows its screens in English, whatever the phone's language:
 * string resources (through [wrap]), and number, date and day-name formatting (through the default
 * [Locale]). The phone's region is kept ("en-GR" still writes dates the local way).
 *
 * Android resets the default locale on every configuration change, so the application
 * re-applies it from onConfigurationChanged.
 */
object AppLanguage {

    @Volatile
    var locale: Locale = Locale.ENGLISH
        private set

    fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()

    private fun english(): Locale {
        val system = systemLocale()
        if (system.language == "en") return system
        return runCatching { Locale.Builder().setLanguage("en").setRegion(system.country).build() }
            .getOrElse { Locale.ENGLISH }
    }

    fun apply() {
        locale = english()
        Locale.setDefault(locale)
    }

    /** Re-applies the default locale after the framework has reset it to the system one. */
    fun reapply() = Locale.setDefault(locale)

    /** [base] with its resources in English. */
    fun wrap(base: Context): Context {
        val target = locale
        if (base.resources.configuration.locales[0] == target) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(target)
        return base.createConfigurationContext(config)
    }
}
