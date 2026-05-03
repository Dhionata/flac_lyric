package services

import interfaces.FileService
import interfaces.MatchService
import interfaces.UserInterface
import java.io.File
import java.util.logging.Logger
import models.FilePair
import org.apache.commons.text.similarity.CosineDistance
import ui.UserInterfaceImpl

class MatchServiceImpl(
    override val userInterface: UserInterface = UserInterfaceImpl(),
    override val fileService: FileService = FileServiceImpl(),
) : MatchService {
    private val logger = Logger.getLogger(this.javaClass.name)

    override fun matchFiles(lyricFiles: List<File>, audioFiles: List<File>): List<FilePair> {
        val matchFilesSet = mutableListOf<FilePair>()

        userInterface.showProgress("Buscando correspondências", lyricFiles.size)
        var processedCount = 0
        lyricFiles.parallelStream().forEach { lyricFile ->
            val matchingAudioFileInSameDir = audioFiles.find { audioFile ->
                audioFile.parentFile == lyricFile.parentFile && audioFile.nameWithoutExtension.equals(
                    lyricFile.nameWithoutExtension, ignoreCase = true
                )
            }

            val bestAudioFileMatch = (matchingAudioFileInSameDir ?: findBestMatch(lyricFile, audioFiles)).also {
                logger.info("Best match for\n${lyricFile.name}\nis\n${it?.name}")
            }

            if (bestAudioFileMatch != null) {
                matchFilesSet.add(FilePair(lyricFile, bestAudioFileMatch))
            }
            
            synchronized(this) {
                processedCount++
                userInterface.updateProgress(processedCount, lyricFile.name)
            }
        }
        userInterface.closeProgress()

        return matchFilesSet.filter {
            it.lyricFile.parentFile != it.audioFile.parentFile || it.lyricFile.nameWithoutExtension != it.audioFile.nameWithoutExtension
        }
    }

    override fun handleFilePairs(filePairs: List<FilePair>) {
        userInterface.showProgress("Organizando arquivos", filePairs.size)
        filePairs.forEachIndexed { index, pair ->
            userInterface.updateProgress(index + 1, pair.lyricFile.name)
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
        userInterface.closeProgress()
    }

    private fun findBestMatch(lyricFile: File, audioFiles: List<File>): File? {
        val lyricLowercaseName = lyricFile.nameWithoutExtension.lowercase()
        val cosineDistance = CosineDistance()

        return audioFiles.minByOrNull { audioFile ->
            cosineDistance.apply(
                audioFile.nameWithoutExtension.lowercase(), lyricLowercaseName
            ).also { distance ->
                logger.info("Distância: $distance\npara lyricFile:\n${lyricFile.name}\n${audioFile.name}\n")
            }
        }
    }
}
