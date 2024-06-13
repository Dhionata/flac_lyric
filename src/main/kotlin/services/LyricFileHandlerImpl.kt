package services

import interfaces.FileService
import interfaces.LyricFileHandler
import models.FilePair
import org.apache.commons.text.similarity.LevenshteinDistance
import java.io.File

class LyricFileHandlerImpl(private val fileService: FileService) : LyricFileHandler {

    private val changedSet = mutableSetOf<String>()
    private val errorSet = mutableSetOf<Exception>()

    override fun getLyricFiles(lyricsDirectory: File): Set<File> {
        return lyricsDirectory.walk().filter { it.extension == "lrc" }.toSet()
    }

    override fun matchFiles(lyricFiles: Set<File>, audioFiles: Set<File>): Set<FilePair> {
        val matchFilesSet = mutableSetOf<FilePair>()
        lyricFiles.parallelStream().forEach { lyricFile ->
            val audioFile = findBestMatch(lyricFile, audioFiles)
            if (audioFile != null) {
                matchFilesSet.add(FilePair(lyricFile, audioFile))
            }
        }
        return matchFilesSet
    }

    override fun handleFilePairs(filePairs: Set<FilePair>, parentDirectory: File, lyricsDirectory: File) {
        filePairs.parallelStream().forEach { pair ->
            if (!pair.lyricFile.parent.equals(pair.audioFile.parent)) {
                moveLyricFile(pair.lyricFile, pair.audioFile.parentFile)
                renameLyricFile(pair.lyricFile, pair.audioFile)
            }
        }

        handleUnmatchedFiles(filePairs, parentDirectory, lyricsDirectory)
    }

    private fun findBestMatch(lyricFile: File, audioFiles: Set<File>): File? {
        return audioFiles.minByOrNull {
            LevenshteinDistance().apply(it.nameWithoutExtension, lyricFile.nameWithoutExtension)
        }
    }

    private fun moveLyricFile(lyricFile: File, targetDir: File) {
        try {
            if (fileService.moveFile(lyricFile, targetDir)) {
                changedSet.add("Arquivo \n${lyricFile.name}\nmovido de\n${lyricFile.parent}\npara\n${targetDir}\n")
            }
        } catch (e: Exception) {
            errorSet.add(e)
        }
    }

    private fun renameLyricFile(lyricFile: File, audioFile: File) {
        if (lyricFile.nameWithoutExtension != audioFile.nameWithoutExtension) {
            try {
                if (fileService.renameFile(lyricFile, "${audioFile.nameWithoutExtension}.lrc")) {
                    changedSet.add("Arquivo ${lyricFile.name} renomeado para ${audioFile.nameWithoutExtension}.lrc")
                }
            } catch (e: Exception) {
                errorSet.add(e)
            }
        }
    }

    private fun handleUnmatchedFiles(filePairs: Set<FilePair>, parentDirectory: File, lyricsDirectory: File) {
        val unmatchedLyrics = getUnmatchedLyricFiles(filePairs, lyricsDirectory)
        if (unmatchedLyrics.isNotEmpty()) {
            val newDirectory = File(parentDirectory, "unmatched_lrc")
            if (!newDirectory.exists()) {
                newDirectory.mkdirs()
            }
            unmatchedLyrics.forEach { lyricFile ->
                moveLyricFile(lyricFile, newDirectory)
            }
            if (lyricsDirectory.walk().filter { it.isFile }.none()) {
                if (lyricsDirectory.delete()) {
                    changedSet.add("Diretório ${lyricsDirectory.name} excluído por não existir mais arquivos .lrc.")
                } else {
                    errorSet.add(Exception("Diretório ${lyricsDirectory.name} não foi exluído."))
                }
            }
        }
    }

    private fun getUnmatchedLyricFiles(filePairs: Set<FilePair>, lyricsDirectory: File): Set<File> {
        val matchedLyrics = filePairs.map { it.lyricFile }
        return lyricsDirectory.walk().filter { it.extension == "lrc" && !matchedLyrics.contains(it) }.toSet()
    }

    override fun getChangedSet(): Set<String> {
        return changedSet
    }

    override fun getErrorList(): Set<Exception> {
        return errorSet
    }
}
