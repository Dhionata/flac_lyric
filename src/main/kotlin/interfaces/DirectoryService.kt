package interfaces

import java.io.File

interface DirectoryService {
    fun getDirectory(dialogTitle: String): File
    fun getTxtFile(dialogTitle: String): File
}
