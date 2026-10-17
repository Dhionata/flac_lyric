package services

import java.io.ByteArrayOutputStream
import java.util.*
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import java.util.logging.StreamHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JdkLogServiceTest {

    private val originalLocale = Messages.getLocale()

    @AfterEach
    fun tearDown() {
        Messages.setLocale(originalLocale)
    }

    @Test
    fun testLogServiceWithPortugueseLocale() {
        Messages.setLocale(Locale.forLanguageTag("pt-BR"))
        val logger = Logger.getLogger("test.pt.logservice")
        val out = ByteArrayOutputStream()
        val handler = StreamHandler(out, SimpleFormatter())
        logger.addHandler(handler)

        val logService = JdkLogService(logger)
        logService.info("log.match.opted_not_to_rename")
        handler.flush()

        val output = out.toString()
        assertTrue(output.contains("[log.match.opted_not_to_rename]"), "Should include the standardized key")
        assertTrue(output.contains("Optou por não renomear"), "Should include Portuguese translation")
    }

    @Test
    fun testLogServiceWithEnglishLocale() {
        Messages.setLocale(Locale.ENGLISH)
        val logger = Logger.getLogger("test.en.logservice")
        val out = ByteArrayOutputStream()
        val handler = StreamHandler(out, SimpleFormatter())
        logger.addHandler(handler)

        val logService = JdkLogService(logger)
        logService.info("log.match.opted_not_to_rename")
        handler.flush()

        val output = out.toString()
        assertTrue(output.contains("[log.match.opted_not_to_rename]"), "Should include the standardized key")
        assertTrue(output.contains("Opted not to rename"), "Should include English translation")
    }
}
