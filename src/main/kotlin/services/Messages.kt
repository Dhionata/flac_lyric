package services

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.jar.JarFile

/**
 * Metadata describing an available UI/log language discovered by [Messages].
 */
data class LocaleOption(
    val tag: String,
    val nativeName: String,
    val isSystemDefault: Boolean = false,
)

/**
 * Service providing internationalized strings from resource bundles (`messages*.properties`),
 * initialized with the system's default locale on startup and supporting live runtime language switching
 * as well as dynamically discovering new language bundles (on classpath or inside `./i18n/`).
 */
object Messages {
    private val control: ResourceBundle.Control =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    private val lock = Any()

    /** Normalized system locale detected when the application started. */
    val systemLocale: Locale = normalizeLocale(Locale.getDefault())

    private var currentLocale: Locale = systemLocale
    private var usingSystemLocale: Boolean = true
    private val baseBundle: ResourceBundle = ResourceBundle.getBundle("messages", Locale.ENGLISH, control)
    private var bundle: ResourceBundle = loadBundle(currentLocale)
    private var externalBundleMap: Map<String, String> = loadExternalOverrides(currentLocale)

    private val builtInNativeNames = mapOf(
        "en" to "English",
        "pt-BR" to "Português (Brasil)",
        "es" to "Español",
    )

    private fun normalizeLocale(locale: Locale): Locale {
        val tag = locale.toLanguageTag()
        return when {
            locale.language.equals("pt", ignoreCase = true) -> Locale.forLanguageTag("pt-BR")
            locale.language.equals("es", ignoreCase = true) -> Locale.forLanguageTag("es")
            locale.language.equals("en", ignoreCase = true) -> Locale.ENGLISH
            hasBundleFor(tag) -> locale
            locale.language.isNotBlank() && hasBundleFor(locale.language) -> Locale.forLanguageTag(locale.language)
            else -> Locale.ENGLISH
        }
    }

    private fun hasBundleFor(tag: String): Boolean {
        if (tag.isBlank()) return false
        val suffix = tag.replace('-', '_')
        val resourceName = "messages_$suffix.properties"
        if (Messages::class.java.classLoader.getResource(resourceName) != null) return true
        val externalFile = File("i18n", resourceName)
        return externalFile.exists() && externalFile.isFile
    }

    private fun loadBundle(locale: Locale): ResourceBundle {
        return try {
            ResourceBundle.getBundle("messages", locale, control)
        } catch (_: MissingResourceException) {
            baseBundle
        }
    }

    private fun loadExternalOverrides(locale: Locale): Map<String, String> {
        val i18nDir = File("i18n")
        if (!i18nDir.exists() || !i18nDir.isDirectory) return emptyMap()

        val tag = toCanonicalTag(locale)
        val candidates = listOfNotNull(
            File(i18nDir, "messages_${tag.replace('-', '_')}.properties"),
            File(i18nDir, "messages_${locale.language}.properties").takeIf { locale.language.isNotBlank() },
            File(i18nDir, "messages.properties").takeIf { tag == "en" }
        )

        val result = linkedMapOf<String, String>()
        for (file in candidates) {
            if (file.exists() && file.isFile) {
                try {
                    val props = Properties()
                    file.inputStream().use { stream ->
                        InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                            props.load(reader)
                        }
                    }
                    props.stringPropertyNames().forEach { k ->
                        result[k] = props.getProperty(k)
                    }
                    break
                } catch (_: Exception) {
                }
            }
        }
        return result
    }

    fun toCanonicalTag(locale: Locale): String {
        val tag = locale.toLanguageTag()
        return when {
            tag == "und" || locale.language.isNullOrBlank() || locale.language == "en" -> "en"
            locale.language == "pt" -> "pt-BR"
            else -> tag
        }
    }

    /**
     * Discovers all available languages from built-in classpath bundles and optional `./i18n/messages_*.properties` files.
     */
    fun getAvailableLocales(externalDir: File = File("i18n")): List<LocaleOption> {
        val discoveredTags = linkedSetOf("en", "pt-BR", "es")

        // Discover any additional messages_*.properties in classpath directories or JAR
        try {
            val urls = Messages::class.java.classLoader.getResources("")
            while (urls.hasMoreElements()) {
                val url = urls.nextElement()
                when (url.protocol) {
                    "file" -> {
                        val dir = File(url.toURI())
                        dir.listFiles { _, name -> name.startsWith("messages_") && name.endsWith(".properties") }
                            ?.forEach { f ->
                                val rawTag = f.name.removePrefix("messages_").removeSuffix(".properties").replace('_', '-')
                                if (rawTag.isNotBlank()) discoveredTags.add(rawTag)
                            }
                    }
                    "jar" -> {
                        val jarPath = url.path.substringAfter("file:").substringBefore("!")
                        val decodedPath = java.net.URLDecoder.decode(jarPath, StandardCharsets.UTF_8.name())
                        val jarFile = File(decodedPath)
                        if (jarFile.exists() && jarFile.isFile) {
                            JarFile(jarFile).use { jar ->
                                jar.entries().asIterator().forEach { entry ->
                                    val name = entry.name
                                    if (!name.contains('/') && name.startsWith("messages_") && name.endsWith(".properties")) {
                                        val rawTag = name.removePrefix("messages_").removeSuffix(".properties").replace('_', '-')
                                        if (rawTag.isNotBlank()) discoveredTags.add(rawTag)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Discover external ./i18n/messages_*.properties files
        if (externalDir.exists() && externalDir.isDirectory) {
            externalDir.listFiles { _, name -> name.startsWith("messages_") && name.endsWith(".properties") }
                ?.forEach { f ->
                    val rawTag = f.name.removePrefix("messages_").removeSuffix(".properties").replace('_', '-')
                    if (rawTag.isNotBlank()) discoveredTags.add(rawTag)
                }
        }

        val sysTag = toCanonicalTag(systemLocale)
        return discoveredTags
            .filter { tag -> tag == "en" || hasBundleFor(tag) || (externalDir.exists() && File(externalDir, "messages_${tag.replace('-', '_')}.properties").exists()) }
            .map { tag ->
                val loc = Locale.forLanguageTag(tag)
                val label = builtInNativeNames[tag]
                    ?: loc.getDisplayName(loc).replaceFirstChar { it.titlecase(loc) }.ifBlank { tag }
                LocaleOption(
                    tag = tag,
                    nativeName = label,
                    isSystemDefault = tag.equals(sysTag, ignoreCase = true)
                )
            }
    }

    /**
     * Retrieves the localized string for [key], falling back to the base English bundle if missing.
     * If the key is not found in either, returns [key] safely instead of throwing an exception.
     */
    fun get(key: String): String {
        synchronized(lock) {
            externalBundleMap[key]?.let { return it }
            return try {
                bundle.getString(key)
            } catch (_: MissingResourceException) {
                try {
                    baseBundle.getString(key)
                } catch (_: MissingResourceException) {
                    key
                }
            }
        }
    }

    /**
     * Retrieves the localized format string for [key] and formats it with [args].
     * Uses [currentLocale] for locale-aware formatting.
     */
    fun get(key: String, vararg args: Any): String {
        val localeSnapshot: Locale
        val format: String?
        synchronized(lock) {
            localeSnapshot = currentLocale
            format = externalBundleMap[key] ?: try {
                bundle.getString(key)
            } catch (_: MissingResourceException) {
                try {
                    baseBundle.getString(key)
                } catch (_: MissingResourceException) {
                    null
                }
            }
        }
        if (format == null) {
            return if (args.isEmpty()) key else "$key: ${args.joinToString(", ")}"
        }
        return try {
            String.format(localeSnapshot, format, *args)
        } catch (_: Exception) {
            if (args.isEmpty()) format else "$format (${args.joinToString(", ")})"
        }
    }

    /**
     * Sets the active locale and reloads the resource bundle.
     * Useful for runtime language switching or automated testing across multiple languages.
     */
    fun setLocale(locale: Locale) {
        synchronized(lock) {
            currentLocale = normalizeLocale(locale)
            usingSystemLocale = toCanonicalTag(currentLocale).equals(toCanonicalTag(systemLocale), ignoreCase = true)
            bundle = loadBundle(currentLocale)
            externalBundleMap = loadExternalOverrides(currentLocale)
        }
    }

    /**
     * Sets the active locale by language tag (e.g., `"pt-BR"`, `"en"`, `"es"`),
     * or `"auto"` / `"system"` to restore the OS default language detected at startup.
     */
    fun setLocaleByTag(tag: String?): Locale {
        synchronized(lock) {
            val cleaned = tag?.trim().orEmpty()
            if (cleaned.isBlank() || cleaned.equals("auto", ignoreCase = true) || cleaned.equals("system", ignoreCase = true)) {
                currentLocale = systemLocale
                usingSystemLocale = true
            } else {
                val parsed = Locale.forLanguageTag(cleaned.replace('_', '-'))
                currentLocale = normalizeLocale(parsed)
                usingSystemLocale = false
            }
            bundle = loadBundle(currentLocale)
            externalBundleMap = loadExternalOverrides(currentLocale)
            return currentLocale
        }
    }

    fun isUsingSystemLocale(): Boolean = synchronized(lock) { usingSystemLocale }

    /**
     * Returns the currently active [Locale].
     */
    fun getLocale(): Locale = synchronized(lock) { currentLocale }

    /**
     * Returns all localized key-value pairs defined directly in the active bundle.
     */
    fun getAll(): Map<String, String> {
        synchronized(lock) {
            return bundle.keySet().sorted().associateWith { key -> get(key) }
        }
    }

    /**
     * Returns all localized key-value pairs for the Web UI, ensuring any keys missing in a partial/custom
     * bundle automatically fall back to the base English bundle.
     */
    fun getAllWithFallback(): Map<String, String> {
        synchronized(lock) {
            val allKeys = (baseBundle.keySet() + bundle.keySet() + externalBundleMap.keys).sorted()
            return allKeys.associateWith { key -> get(key) }
        }
    }
}

