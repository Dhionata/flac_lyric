package comparison.application.usecases

import comparison.domain.models.SaveLyricRequest
import comparison.domain.models.SaveLyricResult
import comparison.domain.ports.LyricFileStorage
import java.io.File
import services.Messages

/**
 * Use case to persist the user's chosen lyrics to the local .lrc file.
 */
class SaveLyricChoiceUseCase(
    private val lyricStorage: LyricFileStorage,
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
                SaveLyricResult(
                    success = true,
                    message = Messages.get("save.success.lrclib", targetFile.name),
                    targetPath = targetFile.absolutePath
                )
            } else {
                // If user customized/adjusted the local lines, save the adjustments!
                if (request.customLrcContent != null && request.customLrcContent.isNotBlank()) {
                    lyricStorage.writeText(targetFile, request.customLrcContent)
                    SaveLyricResult(
                        success = true,
                        message = Messages.get("save.success.local_updated", targetFile.name),
                        targetPath = targetFile.absolutePath
                    )
                } else {
                    // Kept existing local version without changes
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
