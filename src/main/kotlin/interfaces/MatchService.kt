package interfaces

import models.FilePair
import java.io.File

interface MatchService {
    val fileService: FileService
    val userInterface: UserInterface

    fun matchFiles(lyricFiles: List<File>, audioFiles: List<File>): List<FilePair>
    fun handleFilePairs(filePairs: List<FilePair>)
}
