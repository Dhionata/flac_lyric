package comparison.infrastructure.services

import exceptions.OperationCancelledException
import interfaces.DirectoryService
import java.io.File

/**
 * Implementation of [DirectoryService] that maps directory requests from [services.MusicLyricsService]
 * to explicitly supplied directory paths, falling back to a desktop dialog if configured.
 */
class FixedDirectoryService(
    private val musicDir: File? = null,
    private val lyricsDir: File? = null,
    private val outDir: File? = null,
    private val fallbackDirectoryService: DirectoryService? = null
) : DirectoryService {

    private var directoryRequestIndex = 0

    override fun getDirectory(dialogTitle: String): File {
        val lower = dialogTitle.lowercase()
        val target = when {
            lower.contains("música") || lower.contains("music") || lower.contains("base") || lower.contains("espectro") || lower.contains("spectrum") -> {
                musicDir ?: outDir ?: lyricsDir
            }
            lower.contains("letra") || lower.contains("lyric") -> {
                lyricsDir ?: musicDir ?: outDir
            }
            lower.contains("saída") || lower.contains("out") || lower.contains("mover") || lower.contains("move") || lower.contains("destino") -> {
                outDir ?: lyricsDir ?: musicDir
            }
            lower.contains("principal") || lower.contains("main") -> {
                musicDir ?: lyricsDir ?: outDir
            }
            else -> {
                when (directoryRequestIndex++) {
                    0 -> musicDir ?: lyricsDir ?: outDir
                    1 -> lyricsDir ?: outDir ?: musicDir
                    else -> outDir ?: musicDir ?: lyricsDir
                }
            }
        }

        if (target != null && target.exists() && target.isDirectory) {
            return target
        }

        if (fallbackDirectoryService != null) {
            return fallbackDirectoryService.getDirectory(dialogTitle)
        }

        throw OperationCancelledException()
    }

    override fun getTxtFile(dialogTitle: String): File {
        if (fallbackDirectoryService != null) {
            return fallbackDirectoryService.getTxtFile(dialogTitle)
        }
        throw OperationCancelledException()
    }
}
