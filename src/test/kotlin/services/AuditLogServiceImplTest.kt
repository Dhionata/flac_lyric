package services

import java.io.File
import java.util.Locale
import models.AuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AuditLogServiceImplTest {

    @Test
    fun `test buildBeforeAfterDiffSummary highlights modified, added, removed lines and trailing spaces`() {
        val before = "\uFEFF[00:10.00]v1: First line   \n[00:15.00]Removed credit\n[00:20.00]Same line"
        val after = "[00:10.00]First line\n[00:20.00]Same line"

        val diff = AuditLogServiceImpl.buildBeforeAfterDiffSummary(before, after)

        assertTrue(diff.contains("Change Type: MODIFIED"))
        assertTrue(diff.contains("UTF-8 BOM: true -> false"))
        assertTrue(diff.contains("<UTF8-BOM>[00:10.00]v1: First line    <+3 trailing-space(s)>"))
        assertTrue(diff.contains("+ AFTER : [00:10.00]First line"))
    }

    @Test
    fun `test cleaner tags trailing whitespace normalization instead of empty parentheses`() {
        val cleaner = LyricContentCleanerImpl()
        val raw = "[00:10.00]First line with trailing spaces   \n[00:20.00]Second line"
        val res = cleaner.clean(raw)

        assertTrue(res.modified)
        assertEquals(listOf("espaços/formatação"), res.appliedFixes)
    }

    @Test
    fun `test auditLogService writes operation and content modification entries`() {
        val service = AuditLogServiceImpl()
        service.logOperationStart("Test Operation", mapOf("optA" to true, "dir" to "C:/Test"))
        service.logContentModification(
            operation = "Test Clean",
            targetFile = File("TestSong.lrc"),
            beforeContent = "[00:05.00]v1: Hello",
            afterContent = "[00:05.00]Hello",
            metadata = mapOf("appliedFixes" to "v1:/v2:")
        )
        service.logOperationEnd("Test Operation", success = true, processedCount = 1)

        val auditFile = File(LoggingInitializer.getLogsDirectory(), "flac_lyric_audit.log")
        assertTrue(auditFile.exists())
        val content = auditFile.readText()
        assertTrue(content.contains("[AUDIT:OPERATION_START]"))
        assertTrue(content.contains("[AUDIT:CONTENT_MODIFIED]"))
        assertTrue(content.contains("- BEFORE: [00:05.00]v1: Hello"))
        assertTrue(content.contains("+ AFTER : [00:05.00]Hello"))
    }

    @Test
    fun `test queryStructuredEvents supports filters, run grouping, search and pagination`(@TempDir tempDir: File) {
        val eventsFile = File(tempDir, "flac_lyric_events.jsonl")
        val ev1 = AuditEvent(
            id = "ev-1",
            runId = "run-A",
            epochMs = 1000L,
            timestamp = "2026-11-15 10:00:00",
            category = "ANALYZED",
            eventType = "FILE_ANALYZED",
            actionId = 3,
            actionKey = "menu.option.3.title",
            actionFallback = "Clean lyrics",
            messageKey = "audit.event.file_analyzed",
            messageArgs = listOf("Track1.lrc", "Synced"),
            fallbackMessage = "Analyzed: Track1.lrc",
            targetPath = "C:/Music/Track1.lrc",
            targetName = "Track1.lrc"
        )
        val ev2 = AuditEvent(
            id = "ev-2",
            runId = "run-A",
            epochMs = 2000L,
            timestamp = "2026-11-15 10:00:01",
            category = "SKIPPED",
            eventType = "FILE_SKIPPED",
            actionId = 3,
            actionKey = "menu.option.3.title",
            actionFallback = "Clean lyrics",
            messageKey = "audit.event.file_skipped",
            messageArgs = listOf("Song.opus", "Unsupported format"),
            fallbackMessage = "Skipped: Song.opus",
            targetPath = "C:/Music/Song.opus",
            targetName = "Song.opus",
            metadata = mapOf("reasonKey" to "audit.reason.unsupported_tag_format", "reasonArgs" to "opus")
        )
        val ev3 = AuditEvent(
            id = "ev-3",
            runId = "run-B",
            epochMs = 3000L,
            timestamp = "2026-11-15 10:01:00",
            category = "ERROR",
            eventType = "ERROR",
            actionId = 1,
            actionKey = "menu.option.1.title",
            actionFallback = "Analyze",
            messageKey = "audit.event.error",
            messageArgs = listOf("Corrupt.ogg", "Invalid header"),
            fallbackMessage = "Error: Corrupt.ogg",
            targetPath = "C:/Music/Corrupt.ogg",
            targetName = "Corrupt.ogg"
        )

        LoggingInitializer.appendStructuredEvent(ev1, eventsFileOverride = eventsFile)
        LoggingInitializer.appendStructuredEvent(ev2, eventsFileOverride = eventsFile)
        LoggingInitializer.appendStructuredEvent(ev3, eventsFileOverride = eventsFile)

        val all = LoggingInitializer.queryStructuredEvents(eventsFileOverride = eventsFile)
        assertEquals(3, all.totalEvents)
        assertEquals(3, all.totalMatching)
        // Reverse chronological order (newest first)
        assertEquals(listOf("ev-3", "ev-2", "ev-1"), all.events.map { it.id })
        assertEquals(2, all.runs.size)
        assertEquals(1, all.countsByCategory["ERROR"])
        assertEquals(1, all.countsByCategory["SKIPPED"])
        assertEquals(1, all.countsByCategory["ANALYZED"])

        val filteredByCategory = LoggingInitializer.queryStructuredEvents(
            category = "SKIPPED",
            eventsFileOverride = eventsFile
        )
        assertEquals(1, filteredByCategory.totalMatching)
        assertEquals("Song.opus", filteredByCategory.events.first().targetName)

        val filteredByActionAndRun = LoggingInitializer.queryStructuredEvents(
            actionId = 3,
            runId = "run-A",
            limit = 1,
            offset = 1,
            eventsFileOverride = eventsFile
        )
        assertEquals(2, filteredByActionAndRun.totalMatching)
        assertEquals(1, filteredByActionAndRun.events.size)
        assertEquals("ev-1", filteredByActionAndRun.events.first().id)

        val searchResult = LoggingInitializer.queryStructuredEvents(
            search = "Corrupt.ogg",
            eventsFileOverride = eventsFile
        )
        assertEquals(1, searchResult.totalMatching)
        assertEquals("ev-3", searchResult.events.first().id)
    }

    @Test
    fun `test renderEventMessage re-translates structured events and dynamic skip reason across locales`() {
        val originalLocale = Messages.getLocale()
        try {
            val skipEvent = AuditEvent(
                id = "ev-skip",
                runId = "run-1",
                epochMs = 1000L,
                timestamp = "2026-11-15 10:00:00",
                category = "SKIPPED",
                eventType = "FILE_SKIPPED",
                actionId = 3,
                messageKey = "audit.event.file_skipped",
                messageArgs = listOf("Voice.opus", "fallback"),
                fallbackMessage = "Skipped: Voice.opus",
                targetName = "Voice.opus",
                metadata = mapOf("reasonKey" to "audit.reason.unsupported_tag_format", "reasonArgs" to "opus")
            )

            Messages.setLocaleByTag("en")
            val enText = LoggingInitializer.renderEventMessage(skipEvent)
            assertTrue(enText.contains("Voice.opus"), "Expected file name in EN: $enText")
            assertTrue(enText.contains("OPUS") || enText.contains("opus"), "Expected extension in EN: $enText")
            assertTrue(enText.startsWith("Skipped "), "Expected English translation, got: $enText")

            Messages.setLocaleByTag("pt-BR")
            val ptText = LoggingInitializer.renderEventMessage(skipEvent)
            assertTrue(ptText.contains("Voice.opus"), "Expected file name in PT: $ptText")
            assertTrue(ptText.startsWith("Ignorado "), "Expected Portuguese translation, got: $ptText")

            Messages.setLocaleByTag("es")
            val esText = LoggingInitializer.renderEventMessage(skipEvent)
            assertTrue(esText.contains("Voice.opus"), "Expected file name in ES: $esText")
            assertTrue(esText.startsWith("Omitido "), "Expected Spanish translation, got: $esText")

            Messages.setLocaleByTag("auto")
            assertEquals(Messages.systemLocale, Messages.getLocale())
            assertTrue(Messages.isUsingSystemLocale())
        } finally {
            Messages.setLocale(originalLocale)
        }
    }

    @Test
    fun `test AuditRunContext propagates run metadata and AuditLogService extended methods log cleanly`() {
        val service = AuditLogServiceImpl()
        AuditRunContext.withRun(
            actionId = 3,
            actionKey = "menu.option.3.title",
            actionFallback = "Clean lyrics"
        ) {
            val current = AuditRunContext.current()
            assertEquals(3, current?.actionId)
            service.logAnalysis("Test Scan", File("."), "audit.event.analysis", listOf("Scan", 10))
            service.logFileAnalyzed(
                operation = "Test Scan",
                targetFile = File("Song.lrc"),
                messageKey = "audit.event.file_analyzed",
                messageArgs = listOf("Song.lrc", "SYNCED"),
                metadata = mapOf("lines" to 20)
            )
            service.logSkipped("Test Scan", File("Song.opus"), "audit.reason.unsupported_tag_format", listOf("opus"))
            service.logReportGenerated("Test Scan", File("report.txt"), itemCount = 4)
            val broken = File(File("C:/Music"), "Broken.ogg")
            service.logError(
                "Test Scan",
                broken,
                RuntimeException("${broken.name}: Invalid header [${broken.absolutePath}]")
            )
        }
        assertEquals(null, AuditRunContext.current())

        val events = LoggingInitializer.queryStructuredEvents(category = "ERROR", search = "Invalid header").events
        assertTrue(events.isNotEmpty())
        val rendered = LoggingInitializer.renderEventMessage(events.first())
        assertTrue(rendered.contains("Broken.ogg"))
        assertTrue(!rendered.contains("Broken.ogg: Broken.ogg"), "Duplicate filename should be stripped: $rendered")
        assertTrue(!rendered.contains("[${File("C:/Music", "Broken.ogg").absolutePath}]"), "Duplicate path suffix should be stripped: $rendered")
    }
}
