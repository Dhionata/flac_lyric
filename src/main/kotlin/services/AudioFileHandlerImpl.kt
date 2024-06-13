package services

import interfaces.AudioFileHandler
import interfaces.FileService
import java.io.File

class AudioFileHandlerImpl(private val fileService: FileService) : AudioFileHandler {

    private val supportedExtensions = listOf("flac", "mp3", "ogg", "wav", "m4a")

    override fun getAudioFiles(musicDirectory: File): Set<File> {
        val audioFilesSet = mutableSetOf<File>()
        audioFilesSet.addAll(fileService.getFilesByExtension(musicDirectory, supportedExtensions))
        return audioFilesSet
    }
}
