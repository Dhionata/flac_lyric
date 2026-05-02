package interfaces

import java.io.File
import models.FilePair

interface MatchService {
    val userInterface: UserInterface
    val fileService: FileService

    fun matchFiles(lyricFiles: List<File>, audioFiles: List<File>): List<FilePair>
    fun handleFilePairs(filePairs: List<FilePair>)
}
