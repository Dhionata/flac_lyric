package interfaces

import models.FilePair
import java.io.File

interface MatchService {
    val userInterface: UserInterface
    val fileService: FileService

    fun matchFiles(lyricFiles: List<File>, audioFiles: List<File>): List<FilePair>
    fun handleFilePairs(filePairs: List<FilePair>)
}
