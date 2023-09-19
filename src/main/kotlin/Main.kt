import java.awt.Dimension
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.*
import kotlin.system.exitProcess

private val errorList = mutableSetOf<Exception>()
private val movedList = mutableSetOf<String>()
private val frame = JFrame()

fun main() {
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

    val musicDirectory = getDirectory("Selecione o diretório das músicas")
    val lyricsDirectory = getDirectory("Selecione o diretório dos arquivos .lrc")

    printFilePermissions(musicDirectory)
    printFilePermissions(lyricsDirectory)

    if (musicDirectory.canWrite() && lyricsDirectory.canWrite()) {
        val flacFilesMap =
            musicDirectory.walk().filter { it.extension == "flac" }.associateBy { it.nameWithoutExtension }

        val mp3FilesMap = musicDirectory.walk().filter { it.extension == "mp3" }.associateBy { it.nameWithoutExtension }

        val oggFilesMap = musicDirectory.walk().filter { it.extension == "ogg" }.associateBy { it.nameWithoutExtension }

        if (flacFilesMap.isNotEmpty()) {
            processFiles(flacFilesMap, lyricsDirectory)
        } else if (mp3FilesMap.isNotEmpty()) {
            processFiles(mp3FilesMap, lyricsDirectory)
        } else if (oggFilesMap.isNotEmpty()) {
            processFiles(oggFilesMap, lyricsDirectory)
        } else {
            JOptionPane.showMessageDialog(
                frame, "Não foram encontrados arquivos .flac, .mp3 ou .ogg.", "Atenção!", JOptionPane.WARNING_MESSAGE
            )
        }

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
        } else if (movedList.isNotEmpty()) {
            val movedMessage = movedList.joinToString("\n") { it }
            textArea.text = movedMessage
            JOptionPane.showMessageDialog(
                frame,
                scrollPane,
                "Informação",
                JOptionPane.INFORMATION_MESSAGE
            )
        } else {
            JOptionPane.showMessageDialog(frame, "Está tudo no lugar!", "Informação", JOptionPane.INFORMATION_MESSAGE)
        }
    } else {
        JOptionPane.showMessageDialog(
            frame, "Um ou ambos os diretórios selecionados são somente leitura.", "Erro", JOptionPane.ERROR_MESSAGE
        )
    }

    exitProcess(0)
}

fun processFiles(audioFilesMap: Map<String, File>, lyricsDirectory: File) {
    val lyricFiles = lyricsDirectory.walk().filter { it.extension == "lrc" }
    val unmatchedFiles = mutableListOf<File>()

    lyricFiles.forEach { lyricFile ->
        val correspondingFlacFile = audioFilesMap[lyricFile.nameWithoutExtension]
        if (correspondingFlacFile != null) {
            moveLyricFile(lyricFile, correspondingFlacFile)
        } else {
            unmatchedFiles.add(lyricFile)
        }
    }

    if (unmatchedFiles.isNotEmpty() && audioFilesMap.values.isNotEmpty()) {
        val optionPane = JOptionPane.showConfirmDialog(
            frame,
            "Não foi possível encontrar um par para alguns arquivos .lrc\nDeseja criar uma nova pasta para movê-los?",
            "Confirmação",
            JOptionPane.YES_NO_OPTION
        )

        if (optionPane == JOptionPane.YES_OPTION) {
            val newDir = File(audioFilesMap.values.first().parentFile, "unmatched_lrc")
            if (!newDir.exists()) {
                newDir.mkdir()
            }
            unmatchedFiles.forEach { lyricFile ->
                moveLyricFile(lyricFile, newDir)
            }
            if (lyricsDirectory.walk().filter { it.isFile }.none()) {
                lyricsDirectory.delete()
            }
        }
    }
}

fun moveLyricFile(lyricFile: File, targetDir: File): Boolean {
    if (!lyricFile.exists()) {
        System.err.println("O arquivo ${lyricFile.name} não existe.")
        errorList.add(Exception("O arquivo ${lyricFile.name} não existe."))
        return false
    }

    val actualTargetDir = if (targetDir.isDirectory) targetDir else targetDir.parentFile

    if (!actualTargetDir.exists()) {
        actualTargetDir.mkdirs()
    }

    val targetFile = File(actualTargetDir, lyricFile.name)

    if (targetFile.exists()) {
        println("O arquivo ${lyricFile.name} já está no diretório de destino.")
        return false
    } else if (actualTargetDir.parentFile.freeSpace < lyricFile.length()) {
        errorList.add(Exception("Não há espaço suficiente no diretório de destino para o arquivo ${lyricFile.name}."))
        return false
    } else {
        return try {
            Files.move(lyricFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            movedList.add("Arquivo ${lyricFile.name} movido de ${lyricFile.absolutePath} para ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            errorList.add(
                Exception(
                    "Falha ao mover o arquivo\n${lyricFile.name}\nde\n${lyricFile.absolutePath}\npara\n${
                        targetFile.absolutePath
                    }\n${e.javaClass}"
                )
            )
            false
        }
    }
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
