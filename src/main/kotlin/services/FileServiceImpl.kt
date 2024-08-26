package services

import interfaces.FileService
import java.io.File
import java.util.logging.Logger

class FileServiceImpl : FileService {

    private val logger = Logger.getLogger(this.javaClass.name)

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
                    "Arquivo \n${sourceFile.name}\nmovido de\n${sourceFile.parent}\npara\n${targetFile.parent}\n"
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

    override fun sameFilesWithDiffNames(actualTargetDir: File, sourceFile: File): Boolean =
        actualTargetDir.listFiles()?.filter {
            it.isFile && it.extension == "lrc" && filesAreEqual(it, sourceFile)
        }?.isNotEmpty() == true

    override fun renameFile(file: File, newName: String): Boolean {
        val targetFile = File(file.parent, newName)
        return if (targetFile.exists()) {
            throw Exception(
                "Não foi possível renomear!\nJá existe um arquivo\n${targetFile.name}\nno diretório de destino\n${targetFile.parent}.\n"
            )
        } else {
            file.renameTo(targetFile)
        }
    }

    override fun getFilesByExtension(directory: File, supportedExtensions: List<String>): Set<File> {
        return directory.walk().filter { it.isFile && it.extension in supportedExtensions }.toSet()
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
