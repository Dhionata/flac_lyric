import org.apache.commons.text.similarity.LevenshteinDistance
import java.awt.Dimension
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JTextArea
import kotlin.system.exitProcess

private val errorList = mutableSetOf<Exception>()
private val movedList = mutableSetOf<String>()
private val unmatchedLyrics = mutableSetOf<File>()
private val supportedExtensions = listOf("flac", "mp3", "ogg", "wav", "m4a")
private lateinit var musicDirectory: File
private lateinit var lyricsDirectory: File

private fun main() {
    do {
        musicDirectory = getDirectory("Selecione o diretório das músicas")

        printFilePermissions(musicDirectory)

        if (musicDirectory.canWrite()) {
            processFiles()
        } else {
            errorList.add(Exception("\nDiretório $musicDirectory somente leitura!\n"))
            JOptionPane.showMessageDialog(
                null, "Diretório $musicDirectory somente leitura!", "Erro", JOptionPane.ERROR_MESSAGE
            )
        }
    } while (!musicDirectory.canWrite() && !lyricsDirectory.canWrite())

    val (textArea, scrollPane) = ui()

    if (errorList.isNotEmpty()) {
        val errorMessage = errorList.joinToString("\n") { it.message ?: it.toString() }

        textArea.text = errorMessage

        JOptionPane.showMessageDialog(null, scrollPane, "Erros", JOptionPane.ERROR_MESSAGE)
    }
    if (movedList.isNotEmpty()) {
        val movedMessage = movedList.joinToString("\n") { it }
        textArea.text = movedMessage
        JOptionPane.showMessageDialog(null, scrollPane, "Informação", JOptionPane.INFORMATION_MESSAGE)
    }
    if (errorList.isEmpty() && movedList.isEmpty()) {
        JOptionPane.showMessageDialog(null, "Está tudo no lugar!", "Informação", JOptionPane.INFORMATION_MESSAGE)
    }

    exitProcess(0)
}

private fun ui(): Pair<JTextArea, JScrollPane> {
    val textArea = JTextArea()
    textArea.isEditable = false
    textArea.wrapStyleWord = true
    textArea.lineWrap = true

    val scrollPane = JScrollPane(textArea)
    scrollPane.preferredSize = Dimension(800, 600)
    return Pair(textArea, scrollPane)
}

private fun processFiles() {
    val audioFilesMap = getAudioFiles()

    lyricsDirectory = getDirectory("Selecione o diretório dos arquivos .lrc")
    printFilePermissions(lyricsDirectory)

    val lyricFiles = lyricsDirectory.walk().filter { it.extension == "lrc" }

    lyricFiles.forEach { lyricFile ->
        val matched = handleLyricFile(lyricFile, audioFilesMap)
        if (!matched) {
            unmatchedLyrics.add(lyricFile)
        }
    }
    handleUnmatchedFiles(audioFilesMap)
}

private fun getAudioFiles(): MutableSet<File> {
    val audioFiles = mutableSetOf<File>()

    supportedExtensions.parallelStream().forEach { extension ->
        audioFiles.addAll(musicDirectory.walk().filter { it.extension == extension })
    }
    return audioFiles
}

private fun handleLyricFile(lyricFile: File, audioFiles: Set<File>): Boolean {
    val correspondingFile = audioFiles.find { it.nameWithoutExtension == lyricFile.nameWithoutExtension }

    return if (correspondingFile != null) {
        if (lyricFile.parentFile == correspondingFile.parentFile) {
            true
        } else {
            moveLyricFile(lyricFile, correspondingFile)
        }
    } else {
        findAndMovePossiblePairs(lyricFile, audioFiles)
    }
}

private fun findAndMovePossiblePairs(lyricFile: File, audioFiles: Set<File>): Boolean {
    val possiblePairs = findPossiblePairs(audioFiles, lyricFile)

    val result = AtomicBoolean(false)

    if (possiblePairs.isNotEmpty()) {
        possiblePairs.parallelStream().forEach { possiblePair ->
            if (lyricFile.parentFile != possiblePair.parentFile && possiblePair.walk().any {
                    it == lyricFile
                }) {
                val optionPane = JOptionPane.showConfirmDialog(
                    null,
                    "O arquivo '${lyricFile.name}' pode ser pareado com '${possiblePair.name}'.\nDeseja mover o arquivo de\n'${lyricFile.absolutePath}'\npara\n'${possiblePair.absolutePath}'?",
                    "Confirmação",
                    JOptionPane.YES_NO_OPTION
                )
                if (optionPane == JOptionPane.YES_OPTION) {
                    moveLyricFile(lyricFile, possiblePair)
                    rename(lyricFile, possiblePair)
                    result.set(true)
                }
            }
        }

    }
    return result.get()
}

private fun handleUnmatchedFiles(audioFiles: Set<File>) {
    unmatchedLyrics.parallelStream().forEach { lyricFile ->
        findAndMovePossiblePairs(lyricFile, audioFiles)
    }

    val duplicateLyrics = lyricsDirectory.walk().filter { it.extension == "lrc" && audioFiles.contains(it) }

    duplicateLyrics.forEach {
        unmatchedLyrics.add(it)
    }

    if (unmatchedLyrics.isNotEmpty()) {
        val optionPane = JOptionPane.showConfirmDialog(
            null,
            "Não foi possível encontrar um par para alguns arquivos .lrc\nDeseja criar uma nova pasta para movê-los?",
            "Confirmação",
            JOptionPane.YES_NO_OPTION
        )

        if (optionPane == JOptionPane.YES_OPTION) {
            moveUnmatchedFilesToNewDir(musicDirectory.parentFile, lyricsDirectory)
        }
    }

    if (lyricsDirectory.listFiles()?.isEmpty() == true) {
        System.err.println(lyricsDirectory.name)
    }
}

private fun moveUnmatchedFilesToNewDir(parentDir: File, lyricsDirectory: File) {
    val newDir = File(parentDir.absolutePath, "unmatched_lrc")

    if (!newDir.exists()) {
        newDir.mkdir()
    }

    unmatchedLyrics.parallelStream().forEach { lyricFile ->
        moveLyricFile(lyricFile, newDir)
    }

    if (lyricsDirectory.walk().filter { it.isFile }.none()) {
        if (lyricsDirectory.delete()) {
            movedList.add("Diretório ${lyricsDirectory.name} excluído por não existir mais arquivos .lrc.")
        } else {
            errorList.add(Exception("Diretório ${lyricsDirectory.name} não foi exluído."))
        }
    }
}

private fun moveLyricFile(lyricFile: File, targetDir: File): Boolean {
    if (!lyricFile.exists()) {
        System.err.println("\n-- O arquivo ${lyricFile.name} não existe.\n")
        errorList.add(Exception("\n-- O arquivo ${lyricFile.name} não existe.\n"))
        return false
    }

    val actualTargetDir = if (targetDir.isDirectory) targetDir else targetDir.parentFile

    if (!actualTargetDir.exists()) {
        actualTargetDir.mkdirs()
    }

    val targetFile = File(actualTargetDir, lyricFile.name)

    return if (targetFile.exists()) {
        println("Já existe um arquivo ${targetFile.name} no diretório de destino ${targetFile.absolutePath}.")
        false
    } else if (actualTargetDir.parentFile.freeSpace < lyricFile.length()) {
        errorList.add(Exception("— Não há espaço suficiente no diretório de destino para o arquivo ${lyricFile.name}.\n"))
        false
    } else {
        try {
            Files.move(lyricFile.toPath(), targetFile.toPath())
            movedList.add("Arquivo \n${lyricFile.name}\nmovido de\n${lyricFile.absolutePath}\npara\n${targetFile.absolutePath}\n")
            true
        } catch (e: Exception) {
            errorList.add(
                Exception(
                    "Falha ao mover o arquivo\n${lyricFile.name}\nde\n${lyricFile.absolutePath}\npara\n${
                        targetFile.absolutePath
                    }\n${e.javaClass}\n"
                )
            )
            false
        }
    }
}

private fun rename(lyricFile: File, targetFile: File) {
    if (lyricFile.nameWithoutExtension != targetFile.nameWithoutExtension) {
        val option = JOptionPane.showConfirmDialog(
            null,
            "Deseja renomear o arquivo ${lyricFile.name} para ${targetFile.nameWithoutExtension}.lrc?",
            "Renomeação",
            JOptionPane.YES_NO_OPTION
        )
        if (option == JOptionPane.YES_OPTION) {
            if (!lyricFile.renameTo(File(lyricFile.parent, targetFile.nameWithoutExtension + ".lrc"))) {
                errorList.add(Exception("Falha ao renomear o arquivo ${lyricFile.name} para${targetFile.nameWithoutExtension}.lrc"))
            }
        } else {
            return
        }
    }
}

private fun getDirectory(dialogTitle: String): File {
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

private fun printFilePermissions(file: File) {
    println("Permissões da pasta $file\nLeitura: ${file.canRead()}\nEscrita: ${file.canWrite()}\nExecução: ${file.canExecute()}")
}

private fun findPossiblePairs(audioFilesMap: Set<File>, lyricFile: File): List<File> {
    return audioFilesMap.filter { audioFile ->
        LevenshteinDistance().apply(
            audioFile.nameWithoutExtension, lyricFile.nameWithoutExtension
        ) <= (lyricFile.nameWithoutExtension.length / 2)
    }
}
