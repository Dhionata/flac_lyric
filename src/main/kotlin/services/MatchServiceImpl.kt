package services

import interfaces.FileService
import interfaces.MatchService
import interfaces.UserInterface
import java.io.File
import java.util.Collections
import java.util.logging.Logger
import models.FilePair
import models.OperationResult
import org.apache.commons.text.similarity.CosineDistance

/**
 * Implementation of [MatchService] using Cosine Distance calculation to compare
 * file name strings and find the best lyric match for each song.
 */
class MatchServiceImpl(
    private val userInterface: UserInterface,
    private val fileService: FileService,
) : MatchService {
    /** Logger for debugging and tracking similarity. */
    private val logger = Logger.getLogger(this.javaClass.name)

    override fun matchFiles(lyricFiles: List<File>, audioFiles: List<File>): List<FilePair> {
        // Pre-index existing lyric files in the directories of the audio files to quickly determine pairing
        val existingLyricFilePaths = audioFiles.mapNotNull { it.parentFile }.distinct().flatMap { dir ->
            dir.listFiles { _, name -> name.lowercase().endsWith(".lrc") }?.toList() ?: emptyList()
        }.map { it.absolutePath.lowercase() }.toSet()

        // An audio file is already paired if its expected .lrc file path exists
        val unpairedAudioFiles = audioFiles.filterNot { audioFile ->
            val expectedPath = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".lrc").absolutePath.lowercase()
            existingLyricFilePaths.contains(expectedPath)
        }

        // Pre-compute lowercased name without extension for unpaired audio files to optimize similarity searches
        val unpairedAudioFilesWithNames = unpairedAudioFiles.map { it to it.nameWithoutExtension.lowercase() }

        // A lyric file is already paired if it exists in the same folder as a corresponding audio file with the same name
        val unpairedLyricFiles = lyricFiles.filterNot { lyricFile ->
            val expectedAudioFileForLyric = audioFiles.any { audioFile ->
                audioFile.parentFile == lyricFile.parentFile && audioFile.nameWithoutExtension.equals(lyricFile.nameWithoutExtension, ignoreCase = true)
            }
            expectedAudioFileForLyric
        }

        val matchFilesSet = Collections.synchronizedList(mutableListOf<FilePair>())

        userInterface.showProgress(Messages.get("progress.match"), unpairedLyricFiles.size)
        var processedCount = 0
        unpairedLyricFiles.parallelStream().forEach { lyricFile ->
            val matchingAudioFileInSameDir = unpairedAudioFiles.find { audioFile ->
                audioFile.parentFile == lyricFile.parentFile && audioFile.nameWithoutExtension.equals(
                    lyricFile.nameWithoutExtension, ignoreCase = true
                )
            }

            val bestAudioFileMatch = (matchingAudioFileInSameDir ?: findBestMatch(lyricFile, unpairedAudioFilesWithNames)).also {
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
            it.lyricFile.parentFile != it.audioFile.parentFile || !it.lyricFile.nameWithoutExtension.equals(it.audioFile.nameWithoutExtension, ignoreCase = true)
        }
    }

    override fun handleFilePairs(filePairs: List<FilePair>): OperationResult {
        userInterface.showProgress(Messages.get("progress.organize"), filePairs.size)
        var result = OperationResult()
        filePairs.forEachIndexed { index, pair ->
            userInterface.updateProgress(index + 1, pair.lyricFile.name)
            if (!pair.lyricFile.parentFile.equals(pair.audioFile.parentFile)) {
                if (pair.audioFile.nameWithoutExtension.equals(pair.lyricFile.nameWithoutExtension, ignoreCase = true)) {
                    val (_, moveResult) = fileService.moveLyricFile(pair.lyricFile, pair.audioFile.parentFile)
                    result += moveResult
                } else if (fileService.sameFilesWithDiffNames(
                        pair.audioFile.parentFile, pair.lyricFile
                    ) || userInterface.moveAndRename(pair)
                ) {
                    val (lyricFileMoved, moveResult) = fileService.moveLyricFile(pair.lyricFile, pair.audioFile.parentFile)
                    result += moveResult

                    if (lyricFileMoved != null) {
                        val renameResult = fileService.renameLyricFile(lyricFileMoved, pair.audioFile)
                        result += renameResult
                    }
                }
            } else {
                logger.info("The FilePair files ${pair.lyricFile.name} and ${pair.audioFile.name} are already in the correct place!")

                if (userInterface.onlyRename(pair)) {
                    val renameResult = fileService.renameLyricFile(pair.lyricFile, pair.audioFile)
                    result += renameResult
                } else {
                    logger.info("Opted not to rename")
                }
            }
        }
        userInterface.closeProgress()
        return result
    }

    private fun findBestMatch(lyricFile: File, audioFiles: List<Pair<File, String>>): File? {
        if (audioFiles.isEmpty()) return null
        val lyricLowercaseName = lyricFile.nameWithoutExtension.lowercase()
        val cosineDistance = CosineDistance()

        var bestFile: File? = null
        var minDistance = Double.MAX_VALUE

        for ((audioFile, audioLowercaseName) in audioFiles) {
            val distance = cosineDistance.apply(audioLowercaseName, lyricLowercaseName)
            if (distance < minDistance) {
                minDistance = distance
                bestFile = audioFile
            }
        }
        return bestFile
    }
}
