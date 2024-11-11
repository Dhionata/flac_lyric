package handlers

import interfaces.AudioFileHandler
import java.io.File

class AudioFileHandlerImpl : AudioFileHandler {

    private val supportedExtensions = listOf("flac", "mp3", "ogg", "wav", "m4a")

    override fun getAudioFiles(musicDirectory: File): List<File> {
        return musicDirectory.walk().filter { it.isFile && it.extension in supportedExtensions }.toList()
    }
}
