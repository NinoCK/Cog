package com.bestassistant.language

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import com.fastsearch.R
import java.util.Locale

/** The language the assistant listens, answers and shows its screens in. */
enum class AssistantLanguage(val tag: String?, @param:StringRes val label: Int) {
    /** The phone's language when the assistant speaks it, otherwise English. */
    SYSTEM(null, R.string.language_system),
    ENGLISH("en", R.string.language_en),
    GREEK("el", R.string.language_el),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: SYSTEM
    }
}

/**
 * Applies the assistant's language to the whole process: string resources (through [wrap]),
 * and number, date and day-name formatting (through the default [Locale]).
 *
 * Android resets the default locale on every configuration change, so the application
 * re-applies it from onConfigurationChanged.
 */
object AppLanguage {

    private val supported = setOf("en", "el")

    @Volatile
    var locale: Locale = Locale.getDefault()
        private set

    fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()

    fun resolve(language: AssistantLanguage): Locale {
        val system = systemLocale()
        return when (language) {
            AssistantLanguage.SYSTEM -> if (system.language in supported) system else locale("en", system.country)
            // Keep the phone's region when it matches ("el-CY" for Cyprus); otherwise the language's home region.
            AssistantLanguage.GREEK -> if (system.language == "el") system else locale("el", "GR")
            AssistantLanguage.ENGLISH -> if (system.language == "en") system else locale("en", system.country)
        }
    }

    private fun locale(language: String, country: String): Locale =
        runCatching { Locale.Builder().setLanguage(language).setRegion(country).build() }
            .getOrElse { Locale.forLanguageTag(language) }

    fun apply(language: AssistantLanguage) {
        locale = resolve(language)
        Locale.setDefault(locale)
    }

    /** Re-applies the default locale after the framework has reset it to the system one. */
    fun reapply() = Locale.setDefault(locale)

    /** [base] with its resources in the assistant's language. */
    fun wrap(base: Context): Context {
        val target = locale
        if (base.resources.configuration.locales[0] == target) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(target)
        return base.createConfigurationContext(config)
    }

    val isGreek: Boolean get() = locale.language == "el"
}
