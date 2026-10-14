package comparison.application.usecases

import comparison.domain.models.SaveLyricRequest
import comparison.domain.models.SaveLyricResult
import comparison.domain.ports.LyricFileStorage
import java.io.File

/**
 * Use case to persist the user's chosen lyrics to the local .lrc file.
 */
class SaveLyricChoiceUseCase(
    private val lyricStorage: LyricFileStorage
) {
    fun execute(request: SaveLyricRequest): SaveLyricResult {
        val path = request.lyricPath.orEmpty()
        if (path.isBlank()) {
            return SaveLyricResult(
                success = false,
                message = "Caminho do arquivo .lrc não fornecido.",
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
                        message = "O conteúdo da letra do LRCLIB está vazio.",
                        targetPath = path
                    )
                }
                lyricStorage.writeText(targetFile, effectiveContent)
                SaveLyricResult(
                    success = true,
                    message = "Letra do LRCLIB salva com sucesso em '${targetFile.name}'.",
                    targetPath = targetFile.absolutePath
                )
            } else {
                // If user customized/adjusted the local lines, save the adjustments!
                if (!request.customLrcContent.isNullOrBlank()) {
                    lyricStorage.writeText(targetFile, request.customLrcContent)
                    SaveLyricResult(
                        success = true,
                        message = "Letra local atualizada e salva com sucesso em '${targetFile.name}'.",
                        targetPath = targetFile.absolutePath
                    )
                } else {
                    // Kept existing local version without changes
                    SaveLyricResult(
                        success = true,
                        message = "Letra local mantida para '${targetFile.name}'.",
                        targetPath = targetFile.absolutePath
                    )
                }
            }
        } catch (e: Exception) {
            SaveLyricResult(
                success = false,
                message = "Falha ao salvar a letra: ${e.message ?: e.toString()}",
                targetPath = targetFile.absolutePath
            )
        }
    }
}
