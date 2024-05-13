package services

import java.io.File

class AudioFileHandler(private val fileService: FileService) {

    private val supportedExtensions = listOf("flac", "mp3", "ogg", "wav", "m4a")

    fun getAudioFiles(musicDirectory: File): Set<File> {
        val audioFilesSet = mutableSetOf<File>()
        audioFilesSet.addAll(fileService.getFilesByExtension(musicDirectory, supportedExtensions))
        return audioFilesSet
    }
}
