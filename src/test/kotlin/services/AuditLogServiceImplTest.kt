package services

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

        val auditFile = File("logs/flac_lyric_audit.log")
        assertTrue(auditFile.exists())
        val content = auditFile.readText()
        assertTrue(content.contains("[AUDIT:OPERATION_START]"))
        assertTrue(content.contains("[AUDIT:CONTENT_MODIFIED]"))
        assertTrue(content.contains("- BEFORE: [00:05.00]v1: Hello"))
        assertTrue(content.contains("+ AFTER : [00:05.00]Hello"))
    }
}
