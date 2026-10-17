package services

import java.util.*

/**
 * Service providing internationalized strings from resource bundles (`messages.properties`),
 * based on the current locale.
 */
object Messages {
    private val control: ResourceBundle.Control =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    private var currentLocale: Locale = normalizeLocale(Locale.getDefault())
    private var bundle: ResourceBundle = ResourceBundle.getBundle("messages", currentLocale, control)

    private fun normalizeLocale(locale: Locale): Locale {
        return if (locale.language.equals("pt", ignoreCase = true)) {
            Locale.forLanguageTag("pt-BR")
        } else {
            locale
        }
    }

    /**
     * Retrieves the localized string for [key].
     * If the key is not found, returns [key] safely instead of throwing an exception.
     */
    fun get(key: String): String {
        return try {
            bundle.getString(key)
        } catch (_: MissingResourceException) {
            key
        }
    }

    /**
     * Retrieves the localized format string for [key] and formats it with [args].
     * Uses [currentLocale] for locale-aware formatting.
     */
    fun get(key: String, vararg args: Any): String {
        return try {
            val format = bundle.getString(key)
            String.format(currentLocale, format, *args)
        } catch (_: MissingResourceException) {
            if (args.isEmpty()) key else "$key: ${args.joinToString(", ")}"
        }
    }

    /**
     * Sets the active locale and reloads the resource bundle.
     * Useful for user configuration or automated testing across multiple languages.
     */
    fun setLocale(locale: Locale) {
        currentLocale = normalizeLocale(locale)
        bundle = ResourceBundle.getBundle("messages", currentLocale, control)
    }

    /**
     * Returns the currently active [Locale].
     */
    fun getLocale(): Locale = currentLocale
}
