import interfaces.FileService
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

class FileServiceImpl : FileService {

    override fun printFilePermissions(file: File) {
        Logger.getGlobal().log(
            Level.INFO,
            "Permissões da pasta $file\nLeitura: ${file.canRead()}\nEscrita: ${file.canWrite()}\nExecução: ${file.canExecute()}"
        )
    }

    override fun moveFile(sourceFile: File, targetDir: File): Boolean {
        if (!sourceFile.exists()) {
            Exception("\n-- O arquivo ${sourceFile.name} não existe.\n")
        }

        val actualTargetDir = if (targetDir.isDirectory) targetDir else targetDir.parentFile
        if (!actualTargetDir.exists()) {
            actualTargetDir.mkdirs()
        }

        val targetFile = File(actualTargetDir, sourceFile.name)

        return if (targetFile.exists()) {
            Exception("Já existe um arquivo ${targetFile.name} no diretório de destino ${targetFile.parent}.")
            false
        } else if (actualTargetDir.parentFile.freeSpace < sourceFile.length()) {
            Exception("— Não há espaço suficiente no diretório de destino para o arquivo ${sourceFile.name}.\n")
            false
        } else {
            try {
                sourceFile.copyTo(targetFile, overwrite = false)
                sourceFile.delete()
                Logger.getGlobal().log(
                    Level.INFO,
                    "Arquivo \n${sourceFile.name}\nmovido de\n${sourceFile.parent}\npara\n${targetFile.parent}\n"
                )
                true
            } catch (e: Exception) {
                Exception(
                    "Falha ao mover o arquivo\n${sourceFile.name}\nde\n${
                        sourceFile.parent
                    }\npara\n${
                        targetFile.parent
                    }\n${e.javaClass}\n"
                )
                false
            }
        }
    }

    override fun renameFile(file: File, newName: String): Boolean {
        val targetFile = File(file.parent, newName)
        return if (targetFile.exists()) {
            Exception("Já existe um arquivo ${targetFile.name} no diretório de destino ${targetFile.parent}.")
            false
        } else {
            file.renameTo(targetFile)
        }
    }

    override fun getFilesByExtension(directory: File, supportedExtensions: List<String>): Set<File> {
        return directory.walk().filter { it.isFile && it.extension in supportedExtensions }.toSet()
    }
}
