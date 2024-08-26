package services

import interfaces.FileService
import interfaces.LyricFileHandler
import interfaces.UserInterface
import models.FilePair
import org.apache.commons.text.similarity.CosineDistance
import ui.UserInterfaceImpl
import java.io.File

class LyricFileHandlerImpl(
    private val fileService: FileService = FileServiceImpl(),
    private val userInterface: UserInterface = UserInterfaceImpl()
) : LyricFileHandler {

    override val changedSet = mutableSetOf<String>()
    override val errorSet = mutableSetOf<Exception>()

    override fun getLyricFiles(lyricsDirectory: File): Set<File> {
        return lyricsDirectory.walk().filter { it.extension == "lrc" }.toSet()
    }

    override fun matchFiles(lyricFiles: Set<File>, audioFiles: Set<File>): Set<FilePair> {
        val matchFilesSet = mutableSetOf<FilePair>()
        lyricFiles.forEach { lyricFile ->
            val bestAudioFileMatch = findBestMatch(lyricFile, audioFiles)
            if (bestAudioFileMatch != null) {
                matchFilesSet.add(FilePair(lyricFile, bestAudioFileMatch))
            }
        }
        return matchFilesSet
    }

    override fun handleFilePairs(filePairs: Set<FilePair>, musicDirectoryParent: File, lyricsDirectory: File) {

        filePairs.forEach { pair ->
            if (!pair.lyricFile.parentFile.equals(pair.audioFile.parentFile)) {
                if (pair.audioFile.nameWithoutExtension == pair.lyricFile.nameWithoutExtension) {
                    moveLyricFile(pair.lyricFile, pair.audioFile.parentFile)
                } else if (fileService.sameFilesWithDiffNames(
                        pair.audioFile.parentFile, pair.lyricFile
                    ) || userInterface.move(pair)
                ) {
                    val lyricFileMoved = moveLyricFile(pair.lyricFile, pair.audioFile.parentFile)
                    if (lyricFileMoved != null) {
                        renameLyricFile(lyricFileMoved, pair.audioFile)
                    }
                }
            }
        }
    }

    private fun findBestMatch(lyricFile: File, audioFiles: Set<File>): File? {
        return audioFiles.minByOrNull {
            CosineDistance().apply(it.nameWithoutExtension, lyricFile.nameWithoutExtension)
        }
    }

    private fun moveLyricFile(lyricFile: File, targetDir: File): File? {
        try {
            if (fileService.moveFile(lyricFile, targetDir)) {
                changedSet.add("Arquivo \n${lyricFile.name}\nmovido de\n${lyricFile.parent}\npara\n${targetDir}\n")
                return File(targetDir, lyricFile.name)
            }
        } catch (e: Exception) {
            errorSet.add(e)
        }
        return null
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

    override fun handleUnmatchedFiles(
        musicDirectory: File, lyricsDirectory: File
    ) {
        // Cria um mapa de todos os arquivos de música no diretório de música e subdiretórios
        val musicFilesMap =
            musicDirectory.walk().filter { it.isFile && it.extension != "lrc" }.associateBy { it.nameWithoutExtension }

        // Filtra os arquivos .lrc que não têm correspondência no mapa de arquivos de música
        val unmatchedLyricFiles =
            lyricsDirectory.walk().filter { it.isFile && it.extension == "lrc" }.filterNot { lyricFile ->
                musicFilesMap.containsKey(lyricFile.nameWithoutExtension)
            }.toList()

        if (unmatchedLyricFiles.isNotEmpty()) {
            val newDirectory = File(musicDirectory.parentFile, "unmatched_lrc")
            if (!newDirectory.exists()) {
                newDirectory.mkdirs()
            }
            unmatchedLyricFiles.forEach { lyricFile ->
                if (lyricFile.parentFile != newDirectory) {
                    moveLyricFile(lyricFile, newDirectory)
                }
            }
        }

        // Verifica se o diretório de letras está vazio e tenta excluí-lo
        if (lyricsDirectory.walk().filter { it.isFile }.none()) {
            if (lyricsDirectory.delete()) {
                changedSet.add("Diretório ${lyricsDirectory.name} excluído por não existir mais arquivos .lrc.")
            } else {
                errorSet.add(Exception("Diretório ${lyricsDirectory.name} não pôde ser excluído."))
            }
        }
    }
}
