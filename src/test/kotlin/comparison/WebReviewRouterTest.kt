package comparison

import comparison.infrastructure.web.WebReviewRouter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import services.Messages

class WebReviewRouterTest {

    @Test
    fun `test parseQueryParams with ampersand in path parameter preserves full path`() {
        val originalPath = "C:\\Users\\xiyun\\Music\\Bruno & Marrone\\Sonhos, Amores e Sucessos\\Bruno & Marrone - Por um Minuto (Por un Minuto).flac"
        val encodedParam = URLEncoder.encode(originalPath, StandardCharsets.UTF_8.name())
        val rawQuery = "path=$encodedParam"

        val params = WebReviewRouter.parseQueryParams(rawQuery)

        assertEquals(originalPath, params["path"])
    }

    @Test
    fun `test parseQueryParams with multiple parameters and special characters`() {
        val path = "C:\\Musics\\Cailin Russo & Chrissy Costanza - Phoenix.flac"
        val rawQuery = "id=42&path=" + URLEncoder.encode(path, StandardCharsets.UTF_8.name()) + "&q=" + URLEncoder.encode("Bruno & Marrone", StandardCharsets.UTF_8.name())

        val params = WebReviewRouter.parseQueryParams(rawQuery)

        assertEquals("42", params["id"])
        assertEquals(path, params["path"])
        assertEquals("Bruno & Marrone", params["q"])
    }

    @Test
    fun `test parseQueryParams with null or blank query returns empty map`() {
        assertEquals(WebReviewRouter.parseQueryParams(null), emptyMap())
        assertEquals(WebReviewRouter.parseQueryParams(""), emptyMap())
        assertEquals(WebReviewRouter.parseQueryParams("   "), emptyMap())
    }

    @Test
    fun `test Messages getAll returns non-empty map and changes with locale`() {
        val originalLocale = Messages.getLocale()
        try {
            Messages.setLocale(Locale.ENGLISH)
            val enMessages = Messages.getAll()
            Assertions.assertTrue(enMessages.isNotEmpty(), "English messages should not be empty")
            assertEquals("Operations Dashboard", enMessages["web.menu.hero_title"])

            Messages.setLocale(Locale.forLanguageTag("pt-BR"))
            val ptMessages = Messages.getAll()
            Assertions.assertTrue(ptMessages.isNotEmpty(), "Portuguese messages should not be empty")
            assertEquals("Painel Geral de Operações", ptMessages["web.menu.hero_title"])
        } finally {
            Messages.setLocale(originalLocale)
        }
    }

    @Test
    fun `test key symmetry between English and Portuguese bundles`() {
        val originalLocale = Messages.getLocale()
        try {
            Messages.setLocale(Locale.ENGLISH)
            val enKeys = Messages.getAll().keys

            Messages.setLocale(Locale.forLanguageTag("pt-BR"))
            val ptKeys = Messages.getAll().keys

            val missingInPt = enKeys - ptKeys
            val missingInEn = ptKeys - enKeys

            Assertions.assertTrue(missingInPt.isEmpty(), "Keys in EN but missing in PT: $missingInPt")
            Assertions.assertTrue(missingInEn.isEmpty(), "Keys in PT but missing in EN: $missingInEn")
        } finally {
            Messages.setLocale(originalLocale)
        }
    }

    @Test
    fun `test all web keys used in index html are present in messages bundle`() {
        val htmlStream = javaClass.getResourceAsStream("/web/index.html")
        Assertions.assertNotNull(htmlStream, "/web/index.html resource must exist")
        val html = (htmlStream ?: return).bufferedReader().use { it.readText() }

        val tRegex = java.util.regex.Pattern.compile("""t\(\s*['"]([a-zA-Z0-9_.]+)['"]""")
        val dataI18nRegex = java.util.regex.Pattern.compile("""data-i18n(?:-html|-title|-placeholder|-alt)?=["']([a-zA-Z0-9_.]+)["']""")

        val usedKeys = mutableSetOf<String>()
        val tMatcher = tRegex.matcher(html)
        while (tMatcher.find()) {
            usedKeys.add(tMatcher.group(1))
        }
        val dataMatcher = dataI18nRegex.matcher(html)
        while (dataMatcher.find()) {
            usedKeys.add(dataMatcher.group(1))
        }

        val allDefinedKeys = Messages.getAll().keys
        val webKeysUsed = usedKeys.filter { it.startsWith("web.") }
        val missingKeys = webKeysUsed.filterNot { allDefinedKeys.contains(it) }

        Assertions.assertTrue(
            missingKeys.isEmpty(), "All web.* keys in index.html must exist in messages properties, missing: $missingKeys"
        )
    }

    @Test
    fun `test all 9 menu options have non-empty titles and hints in both locales`() {
        val originalLocale = Messages.getLocale()
        try {
            for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("pt-BR"))) {
                Messages.setLocale(locale)
                for (i in 0..8) {
                    val title = Messages.get("menu.option.$i.title")
                    val hint = Messages.get("menu.option.$i.hint")
                    Assertions.assertTrue(title.isNotBlank(), "Title for menu option $i in $locale should not be blank")
                    Assertions.assertTrue(hint.isNotBlank(), "Hint for menu option $i in $locale should not be blank")
                    Assertions.assertFalse(title.startsWith("!"), "Title for menu option $i in $locale missing translation: $title")
                    Assertions.assertFalse(hint.startsWith("!"), "Hint for menu option $i in $locale missing translation: $hint")
                }
            }
        } finally {
            Messages.setLocale(originalLocale)
        }
    }

    @Test
    fun `test index html contains no corrupted replacement tokens`() {
        val htmlStream = javaClass.getResourceAsStream("/web/index.html")
        Assertions.assertNotNull(htmlStream, "/web/index.html resource must exist")
        val html = (htmlStream ?: return).bufferedReader().use { it.readText() }

        Assertions.assertFalse(html.contains("[\\n"), "index.html should not contain broken '[\\\\n' string")
        Assertions.assertFalse(html.contains(",\\n"), "index.html should not contain broken ',\\\\n' string")
        Assertions.assertFalse(html.contains(")\\n"), "index.html should not contain broken ')\\\\n' string")
    }

    @Test
    fun `test shutdown endpoint triggers onShutdownRequested after grace period when last tab closes`() {
        val shutdownLatch = java.util.concurrent.CountDownLatch(1)
        val router = createTestRouter(graceMs = 80L) {
            shutdownLatch.countDown()
        }
        val server = comparison.infrastructure.web.WebLyricReviewServerImpl(router)
        val port = server.start(0)
        try {
            sendHttpPost("http://localhost:$port/api/heartbeat?clientId=tab-1")
            sendHttpPost("http://localhost:$port/api/shutdown?clientId=tab-1")

            Assertions.assertTrue(
                shutdownLatch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS),
                "Server should trigger shutdown callback when the last client closes"
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `test shutdown is cancelled if page reloads and sends heartbeat within grace period`() {
        val shutdownCalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val router = createTestRouter(graceMs = 200L) {
            shutdownCalled.set(true)
        }
        val server = comparison.infrastructure.web.WebLyricReviewServerImpl(router)
        val port = server.start(0)
        try {
            sendHttpPost("http://localhost:$port/api/heartbeat?clientId=tab-old")
            sendHttpPost("http://localhost:$port/api/shutdown?clientId=tab-old")
            // Simulate immediate page reload (new tab clientId)
            Thread.sleep(30)
            sendHttpPost("http://localhost:$port/api/heartbeat?clientId=tab-new")

            Thread.sleep(300)
            Assertions.assertFalse(
                shutdownCalled.get(),
                "Server must NOT shut down when page reloads within grace period"
            )
        } finally {
            server.stop()
        }
    }

    private fun createTestRouter(graceMs: Long, onShutdown: () -> Unit): WebReviewRouter {
        val reportParser = comparison.infrastructure.parsers.LyricsReportParserImpl()
        val metadataExtractor = comparison.infrastructure.audio.AudioMetadataExtractorImpl()
        val lrcClient = object : comparison.domain.ports.LrcLibClient {
            override fun getLyrics(trackName: String, artistName: String, albumName: String?, durationSeconds: Int?) = null
            override fun searchLyrics(query: String) = emptyList<comparison.domain.models.LrcLibLyrics>()
        }
        val storage = comparison.infrastructure.io.LyricFileStorageImpl()
        val lineParser = comparison.infrastructure.parsers.LyricLineParserImpl()
        val parseLines = comparison.application.usecases.ParseLyricLinesUseCase(lineParser)
        val fetchLrc = comparison.application.usecases.FetchLrcLibLyricsUseCase(lrcClient)
        return WebReviewRouter(
            parseReportUseCase = comparison.application.usecases.ParseIssuesReportUseCase(reportParser),
            extractDetailsUseCase = comparison.application.usecases.ExtractTrackDetailsUseCase(
                metadataExtractor, storage, parseLines, fetchLrc
            ),
            saveLyricUseCase = comparison.application.usecases.SaveLyricChoiceUseCase(storage),
            fetchLrcLibUseCase = fetchLrc,
            audioMetadataExtractor = metadataExtractor,
            shutdownGracePeriodMs = graceMs,
            onShutdownRequested = onShutdown
        )
    }

    private fun sendHttpPost(urlStr: String): Int {
        val conn = (java.net.URI.create(urlStr).toURL().openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 2000
            readTimeout = 2000
        }
        conn.outputStream.use { it.write(ByteArray(0)) }
        val code = conn.responseCode
        conn.disconnect()
        return code
    }
}
