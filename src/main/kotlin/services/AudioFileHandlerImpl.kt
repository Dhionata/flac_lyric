package services

import interfaces.AudioFileHandler
import interfaces.FileService
import java.io.File

class AudioFileHandlerImpl(private val fileService: FileService = FileServiceImpl()) : AudioFileHandler {

    private val supportedExtensions = listOf("flac", "mp3", "ogg", "wav", "m4a")

    override fun getAudioFiles(musicDirectory: File): Set<File> {
        return fileService.getFilesByExtension(musicDirectory, supportedExtensions)
    }
}
