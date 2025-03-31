package services

import interfaces.FileService
import java.io.File
import java.nio.file.Paths
import java.util.logging.Logger

class FileServiceImpl : FileService {

    private val logger = Logger.getLogger(this.javaClass.name)
    override val changedSet: MutableSet<String> = mutableSetOf<String>()
    override val errorSet: MutableSet<Exception> = mutableSetOf<Exception>()

    override fun printFilePermissions(file: File) {
        logger.info(
            "Permissões da pasta $file\nLeitura: ${file.canRead()}\nEscrita: ${file.canWrite()}\nExecução: ${file.canExecute()}"
        )
    }

    override fun moveFile(sourceFile: File, targetDir: File): Boolean {
        if (!sourceFile.exists()) {
            throw Exception("\n-- O arquivo ${sourceFile.name} não existe.\n")
        }

        val actualTargetDir = if (targetDir.isDirectory) targetDir else targetDir.parentFile

        if (!actualTargetDir.exists()) {
            actualTargetDir.mkdirs()
        }

        val targetFile = File(actualTargetDir, sourceFile.name)

        return if (targetFile.exists()) {
            val sameFileWithDifferentName = sameFilesWithDiffNames(targetFile.parentFile, sourceFile)
            if (sameFileWithDifferentName && sourceFile.parentFile != targetFile.parentFile) {
                logger.warning(
                    "Será deletado o arquivo\n$sourceFile"
                )
                sourceFile.delete().also {
                    logger.info(
                        "Arquivo\n$sourceFile\nexcluído, já existe um arquivo com o mesmo conteúdo e tamanho no diretório alvo"
                    )
                }

            } else {
                throw Exception(
                    "Já existe um arquivo\n${targetFile.name}\ndo diretório\n${sourceFile.parentFile}\nno diretório de destino\n${
                        targetFile.parent
                    }\nMas conteúdo ou tamanho diferente\n"
                )
            }
        } else if (actualTargetDir.parentFile.freeSpace < sourceFile.length()) {
            throw Exception("— Não há espaço suficiente no diretório de destino para o arquivo ${sourceFile.name}.\n")
        } else {
            try {
                sourceFile.copyTo(targetFile, overwrite = false)
                sourceFile.delete()
                logger.info(
                    "\nArquivo \n${sourceFile.name}\nmovido de\n${sourceFile.parent}\npara\n${targetFile.parent}\n"
                )
                true
            } catch (e: Exception) {
                throw Exception(
                    "Falha ao mover o arquivo\n${sourceFile.name}\nde\n${
                        sourceFile.parent
                    }\npara\n${
                        targetFile.parent
                    }\n${e.javaClass}\n"
                )
            }
        }
    }

    override fun sameFilesWithDiffNames(actualTargetDir: File, sourceFile: File): Boolean = actualTargetDir.walk().filter {
        it.isFile && it.extension == "lrc" && filesAreEqual(it, sourceFile)
    }.any()

    override fun renameFile(file: File, newName: String): Boolean {
        val targetFile = File(file.parent, newName)
        return if (targetFile.exists()) {
            if (filesAreEqual(targetFile, file)) {
                file.delete()
                throw Exception(
                    "Não foi possível renomear!\nJá existe um arquivo\n${targetFile.name}\nno diretório de destino\n${targetFile.parent}\ncom o mesmo conteúdo.\nArquivo ${file.name} excluído\n"
                )
            } else {
                val targetDirectory = File(Paths.get(System.getProperty("user.home"), "Desktop").toString(), "Lyrics With Wrong Name")

                if (!targetDirectory.exists()) {
                    targetDirectory.mkdirs()
                }

                moveFile(file, targetDirectory)
                throw Exception(
                    "Não foi possível renomear!\nJá existe um arquivo\n${targetFile.name}\nno diretório de destino\n${targetFile.parent} com conteúdo diferente.\nArquivo\n${file.name}\nmovido para\n${targetDirectory.absolutePath}\n"
                )
            }
        } else {
            file.renameTo(targetFile)
        }
    }

    override fun moveLyricFile(lyricFile: File, targetDir: File): File? {
        try {
            if (moveFile(lyricFile, targetDir)) {
                changedSet.add("Arquivo \n${lyricFile.name}\nmovido de\n${lyricFile.parent}\npara\n${targetDir}\n")
                return File(targetDir, lyricFile.name)
            } else {
                errorSet.add(Exception("Arquivo ${lyricFile.name} não movido para $targetDir"))
            }
        } catch (e: Exception) {
            errorSet.add(e)
        }
        return null
    }

    override fun renameLyricFile(lyricFile: File, audioFile: File) {
        try {
            if (renameFile(lyricFile, "${audioFile.nameWithoutExtension}.lrc")) {
                changedSet.add("Arquivo ${lyricFile.name} renomeado para ${audioFile.nameWithoutExtension}.lrc")
            }
        } catch (e: Exception) {
            errorSet.add(e)
        }
    }

    override fun handleUnmatchedFiles(
        musicDirectory: File, lyricsDirectory: File
    ) {
        val musicFilesMap = musicDirectory.walk().filter { it.isFile && it.extension != "lrc" }.associateBy { it.nameWithoutExtension }

        val unmatchedLyricFiles = lyricsDirectory.walk().filter { it.isFile && it.extension == "lrc" }.filterNot { lyricFile ->
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

        if (lyricsDirectory.walk().filter { it.isFile }.none()) {
            if (lyricsDirectory.delete()) {
                changedSet.add("Diretório ${lyricsDirectory.name} excluído por não existir mais arquivos .lrc.")
            } else {
                errorSet.add(Exception("Diretório ${lyricsDirectory.name} não pôde ser excluído."))
            }
        }
    }

    private fun filesAreEqual(file1: File, file2: File): Boolean {
        if (file1.length() != file2.length()) return false

        file1.inputStream().use { input1 ->
            file2.inputStream().use { input2 ->
                return input1.buffered().readBytes() contentEquals input2.buffered().readBytes()
            }
        }
    }
}
