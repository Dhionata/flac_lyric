import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main() {
    val frame = JFrame()
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

    val musicDirectory = getDirectory("Selecione o diretório das músicas .flac")
    val lyricsDirectory = getDirectory("Selecione o diretório dos arquivos .lrc")

    printFilePermissions(musicDirectory)
    printFilePermissions(lyricsDirectory)

    if (musicDirectory.canWrite() && lyricsDirectory.canWrite()) {
        val flacFilesMap =
            musicDirectory.walk().filter { it.extension == "flac" }.associateBy { it.nameWithoutExtension }

        val mp3FilesMap = musicDirectory.walk().filter { it.extension == "mp3" }.associateBy { it.nameWithoutExtension }

        processFiles(flacFilesMap, lyricsDirectory)
        processFiles(mp3FilesMap, lyricsDirectory)

        JOptionPane.showMessageDialog(
            frame, "Todos os arquivos .flac e .lrc estão juntos.", "Informação", JOptionPane.INFORMATION_MESSAGE
        )
    } else {
        JOptionPane.showMessageDialog(
            frame,
            "Um ou ambos os diretórios selecionados são somente leitura. Por favor, selecione diretórios que não sejam somente leitura.",
            "Erro",
            JOptionPane.ERROR_MESSAGE
        )
    }

    exitProcess(0)
}

fun processFiles(audioFilesMap: Map<String, File>, lyricsDirectory: File) {
    val lyricFiles = lyricsDirectory.walk().filter { it.extension == "lrc" }.toList()
    val unmatchedFiles = mutableListOf<File>()

    lyricFiles.forEach { lyricFile ->
        val correspondingFlacFile = audioFilesMap[lyricFile.nameWithoutExtension]
        if (correspondingFlacFile != null) {
            moveLyricFile(lyricFile, correspondingFlacFile)
        } else {
            unmatchedFiles.add(lyricFile)
        }
    }

    if (unmatchedFiles.isNotEmpty()) {
        val optionPane = JOptionPane.showConfirmDialog(
            null,
            "Não foi possível encontrar um par para alguns arquivos .lrc\nDeseja criar uma nova pasta para movê-los?",
            "Confirmação",
            JOptionPane.YES_NO_OPTION
        )

        if (optionPane == JOptionPane.YES_OPTION && audioFilesMap.values.isNotEmpty()) {
            val newDir = File(audioFilesMap.values.first().parentFile.parentFile, "unmatched_lrc")
            if (!newDir.exists()) {
                newDir.mkdir()
            }
            unmatchedFiles.forEach { lyricFile ->
                moveLyricFile(lyricFile, newDir)
            }
            if (lyricsDirectory.listFiles().isNullOrEmpty()) {
                lyricsDirectory.delete()
            }
        } else {
            JOptionPane.showMessageDialog(
                null, "Não há arquivos .flac no diretório especificado.", "Informação", JOptionPane.INFORMATION_MESSAGE
            )
        }
    }
}

fun moveLyricFile(lyricFile: File, targetDir: File): Boolean {
    val targetFile = File(targetDir, lyricFile.name)
    return if (!targetFile.exists() && targetDir.parentFile.freeSpace >= lyricFile.length()) {
        try {
            Files.move(lyricFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            println("Arquivo ${lyricFile.name} movido de ${lyricFile.absolutePath} para ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            JOptionPane.showMessageDialog(
                null, "Falha ao mover o arquivo ${lyricFile.name}\n${e.javaClass}", "Erro", JOptionPane.ERROR_MESSAGE
            )
            false
        }
    } else false
}

fun getDirectory(dialogTitle: String): File {
    return JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        this.dialogTitle = dialogTitle
    }.let { chooser ->
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            exitProcess(0)
        }
    }
}

fun printFilePermissions(file: File) {
    println("Permissões da pasta ${file}\nLeitura: ${file.canRead()}\nEscrita: ${file.canWrite()}\nExecução: ${file.canExecute()}")
}
