package ui

import interfaces.UserInterface
import models.FilePair
import java.awt.Dimension
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.UIManager
import kotlin.system.exitProcess

class UserInterfaceImpl : UserInterface {

    init {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    }

    override fun showError(message: String) {
        JOptionPane.showMessageDialog(null, message, "Erro", JOptionPane.ERROR_MESSAGE)
    }

    override fun showResult(changedSet: Set<String>, errorSet: Set<Exception>) {
        fun showMessageDialog(content: String, title: String, messageType: Int) {
            val textArea = JTextArea(content).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            val scrollPane = JScrollPane(textArea).apply {
                preferredSize = Dimension(500, 300)
            }
            JOptionPane.showMessageDialog(null, scrollPane, title, messageType)
        }

        if (changedSet.isNotEmpty()) {
            val movedMessage = changedSet.joinToString("\n") { it }
            showMessageDialog(movedMessage, "Informação", JOptionPane.INFORMATION_MESSAGE)
        }

        if (errorSet.isNotEmpty()) {
            val errorMessage = errorSet.joinToString("\n") { it.message ?: it.toString() }
            showMessageDialog(errorMessage, "Erros", JOptionPane.ERROR_MESSAGE)
        }

        if (errorSet.isEmpty() && changedSet.isEmpty()) {
            JOptionPane.showMessageDialog(null, "Está tudo no lugar!", "Informação", JOptionPane.INFORMATION_MESSAGE)
        }
    }

    override fun moveAndRename(filePairs: FilePair): Boolean {
        val result = JOptionPane.showConfirmDialog(
            null,
            "Arquivo\n${filePairs.lyricFile.name}\nSerá Movido de\n${filePairs.lyricFile.parentFile}\nPara\n${
                filePairs.audioFile.parentFile
            }\ne Renomeado para\n${filePairs.audioFile.nameWithoutExtension}.lrc\njunto a\n${filePairs.audioFile.name}",
            "Deseja continuar?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        if (result == JOptionPane.CLOSED_OPTION) {
            exitProcess(0)
        }

        return result == JOptionPane.YES_OPTION
    }

    override fun onlyRename(filePair: FilePair): Boolean {
        val result = JOptionPane.showConfirmDialog(
            null,
            "Arquivo\n${filePair.lyricFile.name}\nSerá Renomeado para\n${filePair.audioFile.nameWithoutExtension}.lrc",
            "Deseja continuar?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        if (result == JOptionPane.CLOSED_OPTION) {
            exitProcess(0)
        }

        return result == JOptionPane.YES_NO_OPTION
    }

    override fun option(): Int {
        val options = arrayOf("Organizar Música e Lyrics", "Listar nome dos arquivos sem .lrc")
        val message = "Escolha uma das opções abaixo:"

        val option = JOptionPane.showOptionDialog(
            null, message, "Selecione", JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]
        )

        if (option == -1) {
            exitProcess(0)
        }

        return option
    }

}
