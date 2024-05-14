package interfaces

import java.io.File

interface FileService {
    fun printFilePermissions(file: File)
    fun moveFile(sourceFile: File, targetDir: File): Boolean
    fun renameFile(file: File, newName: String): Boolean
    fun getFilesByExtension(directory: File, supportedExtensions: List<String>): Set<File>
}
