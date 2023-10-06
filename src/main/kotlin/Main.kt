import org.apache.commons.text.similarity.LevenshteinDistance
import java.awt.Dimension
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors
import javax.swing.*
import kotlin.system.exitProcess

private val errorList = mutableSetOf<Exception>()
private val movedList = mutableSetOf<String>()
private val unmatchedLyrics = mutableSetOf<File>()
private val frame = JFrame()
private val supportedExtensions = listOf("flac", "mp3", "ogg", "wav", "m4a")
private lateinit var musicDirectory: File
private lateinit var lyricsDirectory: File

private fun main() {
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

    do {
        musicDirectory = getDirectory("Selecione o diretório das músicas")
        lyricsDirectory = getDirectory("Selecione o diretório dos arquivos .lrc")

        printFilePermissions(musicDirectory)
        printFilePermissions(lyricsDirectory)

        if (musicDirectory.canWrite() && lyricsDirectory.canWrite()) {
            processFiles()
        } else {
            JOptionPane.showMessageDialog(
                frame, "Um ou ambos os diretórios selecionados são somente leituras.", "Erro", JOptionPane.ERROR_MESSAGE
            )
        }
    } while (!musicDirectory.canWrite() && !lyricsDirectory.canWrite())

    val textArea = JTextArea()
    textArea.isEditable = false
    textArea.wrapStyleWord = true
    textArea.lineWrap = true

    val scrollPane = JScrollPane(textArea)
    scrollPane.preferredSize = Dimension(800, 600)

    if (errorList.isNotEmpty()) {
        val errorMessage = errorList.joinToString("\n") { it.message ?: it.toString() }

        textArea.text = errorMessage

        JOptionPane.showMessageDialog(
            frame, scrollPane, "Erros", JOptionPane.ERROR_MESSAGE
        )
    }
    if (movedList.isNotEmpty()) {
        val movedMessage = movedList.joinToString("\n") { it }
        textArea.text = movedMessage
        JOptionPane.showMessageDialog(
            frame, scrollPane, "Informação", JOptionPane.INFORMATION_MESSAGE
        )
    }
    if (errorList.isEmpty() && movedList.isEmpty()) {
        JOptionPane.showMessageDialog(frame, "Está tudo no lugar!", "Informação", JOptionPane.INFORMATION_MESSAGE)
    }

    exitProcess(0)
}

private fun processFiles() {
    val audioFilesMap = getAudioFilesMap(musicDirectory)
    val lyricFiles = lyricsDirectory.walk().filter { it.extension == "lrc" }.toList()

    lyricFiles.parallelStream().forEach { lyricFile ->
        val matched = handleLyricFile(lyricFile, audioFilesMap)
        if (!matched) {
            unmatchedLyrics.add(lyricFile)
        }
    }

    handleUnmatchedFiles(audioFilesMap)
}

private fun getAudioFilesMap(musicDirectory: File): MutableMap<String, File> {
    val audioFilesMap = mutableMapOf<String, File>()
    supportedExtensions.parallelStream().forEach { extension ->
        audioFilesMap.putAll(musicDirectory.walk().filter { it.extension == extension }
            .associateBy { it.nameWithoutExtension })
    }
    return audioFilesMap
}

private fun handleLyricFile(lyricFile: File, audioFilesMap: Map<String, File>): Boolean {
    val correspondingFile = audioFilesMap[lyricFile.nameWithoutExtension]
    return if (correspondingFile != null) {
        if (lyricFile.parentFile == correspondingFile.parentFile) {
            true
        } else {
            moveLyricFile(lyricFile, correspondingFile)
        }
    } else {
        findAndMovePossiblePairs(lyricFile, audioFilesMap)
    }
}

private fun findAndMovePossiblePairs(lyricFile: File, audioFilesMap: Map<String, File>): Boolean {
    val possiblePairs = findPossiblePairs(audioFilesMap, lyricFile)
    val result = AtomicBoolean(false)

    if (possiblePairs.isNotEmpty()) {
        possiblePairs.parallelStream().forEach { possiblePair ->
            if (lyricFile.parentFile != possiblePair.parentFile && possiblePair.walk().any {
                    it == lyricFile
                }) {
                val optionPane = JOptionPane.showConfirmDialog(
                    frame,
                    "O arquivo '${lyricFile.name}' pode ser pareado com '${possiblePair.name}'.\nDeseja mover o arquivo de '${lyricFile.absolutePath}' para '${possiblePair.absolutePath}'?",
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

private fun handleUnmatchedFiles(audioFilesMap: Map<String, File>) {
    unmatchedLyrics.parallelStream().forEach { lyricFile ->
        findAndMovePossiblePairs(lyricFile, audioFilesMap)
    }

    val duplicateLyrics = lyricsDirectory.walk().filter { it.extension == "lrc" && it.name in audioFilesMap.keys }
    duplicateLyrics.forEach { unmatchedLyrics.add(it) }

    if (unmatchedLyrics.isNotEmpty()) {
        val optionPane = JOptionPane.showConfirmDialog(
            frame,
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

    if (targetFile.exists()) {
        println("Já existe um arquivo ${targetFile.name} no diretório de destino.")
        return false
    } else if (actualTargetDir.parentFile.freeSpace < lyricFile.length()) {
        errorList.add(
            Exception("— Não há espaço suficiente no diretório de destino para o arquivo ${lyricFile.name}.\n")
        )
        return false
    } else {
        return try {
            Files.move(lyricFile.toPath(), targetFile.toPath())
            movedList.add(
                "Arquivo \n${lyricFile.name}\nmovido de\n${lyricFile.absolutePath}\npara\n${targetFile.absolutePath}\n"
            )
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
            frame,
            "Deseja renomear o arquivo ${lyricFile.name} para ${targetFile.nameWithoutExtension}.lrc?",
            "Renomeação",
            JOptionPane.YES_NO_OPTION
        )
        if (option == JOptionPane.YES_OPTION) {
            if (!lyricFile.renameTo(File(lyricFile.parent, targetFile.nameWithoutExtension + ".lrc"))) {
                errorList.add(
                    Exception("Falha ao renomear o arquivo ${lyricFile.name} para ${targetFile.nameWithoutExtension}.lrc")
                )
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
    println(
        "Permissões da pasta $file\nLeitura: ${file.canRead()}\nEscrita: ${file.canWrite()}\nExecução: ${file.canExecute()}"
    )
}

private fun findPossiblePairs(audioFilesMap: Map<String, File>, lyricFile: File): List<File> {
    return audioFilesMap.values.parallelStream().filter { audioFile ->
        LevenshteinDistance().apply(
            audioFile.nameWithoutExtension, lyricFile.nameWithoutExtension
        ) <= (lyricFile.nameWithoutExtension.length / 2)
    }.collect(Collectors.toList())
}
