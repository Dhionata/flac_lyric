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

        if (errorSet.isNotEmpty()) {
            val errorMessage = errorSet.joinToString("\n") { it.message ?: it.toString() }
            showMessageDialog(errorMessage, "Erros", JOptionPane.ERROR_MESSAGE)
        }

        if (changedSet.isNotEmpty()) {
            val movedMessage = changedSet.joinToString("\n") { it }
            showMessageDialog(movedMessage, "Informação", JOptionPane.INFORMATION_MESSAGE)
        }

        if (errorSet.isEmpty() && changedSet.isEmpty()) {
            JOptionPane.showMessageDialog(null, "Está tudo no lugar!", "Informação", JOptionPane.INFORMATION_MESSAGE)
        }
    }


    override fun move(filePairs: FilePair): Boolean {
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
}
