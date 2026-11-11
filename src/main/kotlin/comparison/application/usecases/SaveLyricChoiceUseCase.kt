package comparison.application.usecases

import comparison.domain.models.SaveLyricRequest
import comparison.domain.models.SaveLyricResult
import comparison.domain.ports.LyricFileStorage
import interfaces.AuditLogService
import java.io.File
import services.AuditLogServiceImpl
import services.Messages

/**
 * Use case to persist the user's chosen lyrics to the local .lrc file,
 * recording a full before-and-after audit trail of any content changes.
 */
class SaveLyricChoiceUseCase(
    private val lyricStorage: LyricFileStorage,
    private val auditLogService: AuditLogService = AuditLogServiceImpl(),
) {
    fun execute(request: SaveLyricRequest): SaveLyricResult {
        val path = request.lyricPath.orEmpty()
        if (path.isBlank()) {
            return SaveLyricResult(
                success = false,
                message = Messages.get("save.error.missing_path"),
                targetPath = ""
            )
        }

        val targetFile = File(path)
        val beforeContent = if (targetFile.exists() && targetFile.isFile) {
            try {
                lyricStorage.readText(targetFile)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        return try {
            val choice = request.choice.orEmpty()
            val effectiveContent = request.resolvedContent()

            if (choice.equals("LRCLIB", ignoreCase = true)) {
                if (effectiveContent.isBlank()) {
                    return SaveLyricResult(
                        success = false,
                        message = Messages.get("save.error.empty_content"),
                        targetPath = path
                    )
                }
                lyricStorage.writeText(targetFile, effectiveContent)
                auditLogService.logContentModification(
                    operation = "Comparator Save [ONLINE / LRCLIB]",
                    targetFile = targetFile,
                    beforeContent = beforeContent,
                    afterContent = effectiveContent,
                    metadata = mapOf("choice" to "LRCLIB")
                )
                SaveLyricResult(
                    success = true,
                    message = Messages.get("save.success.lrclib", targetFile.name),
                    targetPath = targetFile.absolutePath
                )
            } else {
                // If user customized/adjusted the local lines, save the adjustments!
                if (request.customLrcContent != null && request.customLrcContent.isNotBlank()) {
                    lyricStorage.writeText(targetFile, request.customLrcContent)
                    auditLogService.logContentModification(
                        operation = "Comparator Save [LOCAL ADJUSTED]",
                        targetFile = targetFile,
                        beforeContent = beforeContent,
                        afterContent = request.customLrcContent,
                        metadata = mapOf("choice" to "LOCAL_UPDATED")
                    )
                    SaveLyricResult(
                        success = true,
                        message = Messages.get("save.success.local_updated", targetFile.name),
                        targetPath = targetFile.absolutePath
                    )
                } else {
                    // Kept existing local version without changes
                    auditLogService.logOperationEnd(
                        actionName = "Comparator Keep Local Unchanged",
                        success = true,
                        processedCount = 1,
                        details = listOf(targetFile.absolutePath)
                    )
                    SaveLyricResult(
                        success = true,
                        message = Messages.get("save.success.local_kept", targetFile.name),
                        targetPath = targetFile.absolutePath
                    )
                }
            }
        } catch (e: Exception) {
            SaveLyricResult(
                success = false,
                message = Messages.get("save.error.failed", e.message ?: e.toString()),
                targetPath = targetFile.absolutePath
            )
        }
    }
}

