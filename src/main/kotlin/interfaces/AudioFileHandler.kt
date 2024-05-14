package interfaces

import java.io.File

interface AudioFileHandler {
    fun getAudioFiles(musicDirectory: File): Set<File>
}
