package services

import interfaces.FileService
import interfaces.MatchService
import interfaces.UserInterface

import models.FilePair
import org.apache.commons.text.similarity.CosineDistance
import ui.UserInterfaceImpl
import java.io.File
import java.util.logging.Logger
import kotlin.collections.forEach

class MatchServiceImpl(
    override val fileService: FileService = FileServiceImpl(),
    override val userInterface: UserInterface = UserInterfaceImpl()
) : MatchService {
    private val logger = Logger.getLogger(this.javaClass.name)

    override fun matchFiles(lyricFiles: Set<File>, audioFiles: Set<File>): List<FilePair> {
        val matchFilesSet = mutableListOf<FilePair>()

        lyricFiles.parallelStream().forEach { lyricFile ->
            val matchingAudioFileInSameDir = audioFiles.find { audioFile ->
                audioFile.parentFile == lyricFile.parentFile && audioFile.nameWithoutExtension.equals(
                    lyricFile.nameWithoutExtension, ignoreCase = true
                )
            }

            val bestAudioFileMatch = matchingAudioFileInSameDir ?: findBestMatch(lyricFile, audioFiles).also {
                logger.info("Best match for\n${lyricFile.name}\nis\n${it?.name}")
            }

            if (bestAudioFileMatch != null) {
                matchFilesSet.add(FilePair(lyricFile, bestAudioFileMatch))
            }
        }

        return matchFilesSet.filter {
            it.lyricFile.parentFile != it.audioFile.parentFile || it.lyricFile.nameWithoutExtension != it.audioFile.nameWithoutExtension
        }
    }

    override fun handleFilePairs(filePairs: List<FilePair>) {
        filePairs.forEach { pair ->
            if (!pair.lyricFile.parentFile.equals(pair.audioFile.parentFile)) {
                if (pair.audioFile.nameWithoutExtension == pair.lyricFile.nameWithoutExtension) {
                    fileService.moveLyricFile(pair.lyricFile, pair.audioFile.parentFile)
                } else if (fileService.sameFilesWithDiffNames(
                        pair.audioFile.parentFile, pair.lyricFile
                    ) || userInterface.moveAndRename(pair)
                ) {
                    val lyricFileMoved = fileService.moveLyricFile(pair.lyricFile, pair.audioFile.parentFile)
                    if (lyricFileMoved != null) {
                        fileService.renameLyricFile(lyricFileMoved, pair.audioFile)
                    }
                }
            } else {
                logger.info("Os arquivos de FilePair ${pair.lyricFile.name} e ${pair.audioFile.name} já estão no lugar correto!")

                if (userInterface.onlyRename(pair)) {
                    fileService.renameLyricFile(pair.lyricFile, pair.audioFile)
                } else {
                    logger.info("Optou por não renomear")
                }
            }
        }
    }

    private fun findBestMatch(lyricFile: File, audioFiles: Set<File>): File? {
        val cosineDistance = CosineDistance()
        return audioFiles.minByOrNull { audioFile ->
            cosineDistance.apply(
                audioFile.nameWithoutExtension.lowercase(), lyricFile.nameWithoutExtension.lowercase()
            ).also { distance ->
                logger.info("Distância: $distance\npara lyricFile:\n${lyricFile.name}\n${audioFile.name}\n")
            }
        }
    }
}
