package services

import handlers.AudioFileHandlerImpl
import handlers.LyricFileHandlerImpl
import interfaces.AudioFileHandler
import interfaces.DirectoryService
import interfaces.FileService
import interfaces.LyricFileHandler
import interfaces.MatchService
import interfaces.UserInterface
import java.io.File
import ui.UserInterfaceImpl

class MusicLyricsService(
    private val userInterface: UserInterface = UserInterfaceImpl(),
    private val directoryService: DirectoryService = DirectoryServiceImpl(),
    private val audioFileHandler: AudioFileHandler = AudioFileHandlerImpl(),
    private val lyricFileHandler: LyricFileHandler = LyricFileHandlerImpl(),
    private val matchService: MatchService = MatchServiceImpl(userInterface),
    private val fileService: FileService = FileServiceImpl(),
) {

    fun organizeMusicAndLyrics() {
        val musicDirectory = directoryService.getDirectory("Selecione o diretório das músicas")

        if (!musicDirectory.canWrite()) {
            userInterface.showError("Diretório $musicDirectory somente leitura!")
            return
        }

        val lyricsDirectory = directoryService.getDirectory("Selecione o diretório dos arquivos .lrc")

        if (!lyricsDirectory.canWrite()) {
            userInterface.showError("Diretório $lyricsDirectory somente leitura!")
            return
        }

        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        val filePairs = matchService.matchFiles(lyricFiles, audioFiles)

        matchService.handleFilePairs(filePairs)

        fileService.handleUnmatchedFiles(musicDirectory, lyricsDirectory)

        userInterface.showResult(fileService.changedSet, fileService.errorSet)
    }

    fun findMusicWithoutLyricsPair(): List<File> {
        val musicDirectory = directoryService.getDirectory("Selecione o diretório das músicas")

        if (!musicDirectory.canRead()) {
            userInterface.showError("Diretório $musicDirectory sem premissão de leitura!")
            throw RuntimeException("Diretório $musicDirectory sem premissão de leitura!")
        }

        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)

        val musicFilesWithoutLyrics = audioFiles.filter { audioFile ->
            audioFile.parentFile.walk().none { audioFileParent ->
                audioFileParent.name.equals(audioFile.nameWithoutExtension + ".lrc")
            }
        }

        File("MusicsWithoutLyrics_${musicFilesWithoutLyrics.hashCode()}.txt").writeText(
            musicFilesWithoutLyrics.joinToString("\n") { it.name }
        )

        return musicFilesWithoutLyrics
    }

    fun findLyricsWithoutSync(): List<File> {
        val lyricsDirectory = directoryService.getDirectory("Selecione o diretório dos arquivos .lrc")
        val outDirectory = directoryService.getDirectory("Selecione o diretório de saída")
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)
        val lyricsFilesWithoutSync = lyricFiles.filter { lyricFile ->
            lyricFile.readLines().none { line -> line.contains(Regex("\\d")) }
        }

        File("LyricsWithoutSync_${lyricsFilesWithoutSync.hashCode()}.txt").writeText(
            lyricsFilesWithoutSync.joinToString("\n") { it.name }
        )

        lyricsFilesWithoutSync.forEach { lyric ->
            fileService.moveLyricFile(lyric, outDirectory)
        }

        userInterface.showResult(fileService.changedSet, fileService.errorSet)

        return lyricsFilesWithoutSync
    }

    fun findLyricsWithV1Text(): List<File> {
        val lyricsDirectory = directoryService.getDirectory("Selecione o diretório dos arquivos .lrc")
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)
        val lyricsFilesWithV1 = lyricFiles.filter { lyricFiles ->
            lyricFiles.readLines().any { line -> line.contains("v1:") }
        }

        File("LyricsWithV1_${lyricsFilesWithV1.hashCode()}.txt").writeText(
            lyricsFilesWithV1.joinToString("\n") { it.name }
        )

        lyricsFilesWithV1.forEach { lyricFile ->
            lyricFile.readLines().forEach { line ->
                if (line.contains("v1:")) {
                    val newLine = line.replace("v1:", "")
                    lyricFile.writeText(lyricFile.readText().replace(line, newLine))
                    fileService.changedSet.add(lyricFile.name)
                }
            }
        }

        userInterface.showResult(fileService.changedSet, fileService.errorSet)

        return lyricsFilesWithV1
    }

    fun findAndMoveAloneLyrics(): List<File> {
        val mainDirectory = directoryService.getDirectory("Selecione o diretório contendo as músicas e os arquivos .lrc")
        val outDirectory = directoryService.getDirectory("Selecione o diretório para onde mover os arquivos .lrc sozinhos")

        val lyricFiles = lyricFileHandler.getLyricFiles(mainDirectory)
        val audioFiles = audioFileHandler.getAudioFiles(mainDirectory)

        val audioNamesWithoutExtension = audioFiles.map { it.nameWithoutExtension }.toSet()

        val aloneLyrics = lyricFiles.filter { lyricFile ->
            !audioNamesWithoutExtension.contains(lyricFile.nameWithoutExtension)
        }

        val movedLyricsNames = mutableListOf<String>()

        aloneLyrics.forEach { lyric ->
            val parentFolder = lyric.parentFile
            fileService.moveLyricFile(lyric, outDirectory)
            movedLyricsNames.add(lyric.name)

            if (parentFolder.isDirectory && parentFolder.listFiles()?.isEmpty() == true) {
                if (parentFolder.delete()) {
                    fileService.changedSet.add("Pasta vazia excluída: ${parentFolder.absolutePath}")
                }
            }
        }

        if (movedLyricsNames.isNotEmpty()) {
            File("AloneLyricsMoved_${movedLyricsNames.hashCode()}.txt").writeText(
                movedLyricsNames.joinToString("\n")
            )
        }

        userInterface.showResult(fileService.changedSet, fileService.errorSet)

        return aloneLyrics
    }

    fun verifyFileNameStructure(): List<File> {
        val musicDirectory = directoryService.getDirectory("Selecione o diretório das músicas")

        if (!musicDirectory.canRead()) {
            userInterface.showError("Diretório $musicDirectory sem premissão de leitura!")
            throw RuntimeException("Diretório $musicDirectory sem premissão de leitura!")
        }

        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)
        val incorrectlyNamedFiles = mutableListOf<File>()

        for (audioFile in audioFiles) {
            if (audioFile.extension.lowercase() != "flac") continue

            var isValid = false
            try {
                val audioFileObj = org.jaudiotagger.audio.AudioFileIO.read(audioFile)
                val tag = audioFileObj.tag
                if (tag != null) {
                    val artist = tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST)
                    val title = tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)
                    if (artist.isNotBlank() && title.isNotBlank()) {
                        val expectedName = "$artist - $title"
                        if (audioFile.nameWithoutExtension == expectedName) {
                            isValid = true
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore and use fallback
            }

            if (!isValid) {
                // Fallback: verificar se existe ' - ' no nome separando o suposto Artista e Música
                val regex = Regex("^.+ - .+$")
                if (regex.matches(audioFile.nameWithoutExtension)) {
                    isValid = true
                }
            }

            if (!isValid) {
                incorrectlyNamedFiles.add(audioFile)
            }
        }

        if (incorrectlyNamedFiles.isNotEmpty()) {
            val outDirectory = directoryService.getDirectory("Selecione o diretório para mover os arquivos com nome incorreto")

            val movedNames = mutableListOf<String>()
            incorrectlyNamedFiles.forEach { file ->
                if (fileService.moveFile(file, outDirectory)) {
                    movedNames.add(file.name)
                } else {
                    fileService.errorSet.add(RuntimeException("Falha ao mover o arquivo: ${file.name}"))
                }
            }

            File("IncorrectlyNamedFilesMoved_${incorrectlyNamedFiles.hashCode()}.txt").writeText(
                incorrectlyNamedFiles.joinToString("\n") { it.name }
            )
            fileService.changedSet.add("Movidos ${movedNames.size} de ${incorrectlyNamedFiles.size} arquivos FLAC fora do padrão para ${outDirectory.absolutePath}. Verifique o arquivo de texto gerado.")
        } else {
            fileService.changedSet.add("Todos os arquivos FLAC verificados estão com a estrutura de nome correta.")
        }

        userInterface.showResult(fileService.changedSet, fileService.errorSet)

        return incorrectlyNamedFiles
    }
}
