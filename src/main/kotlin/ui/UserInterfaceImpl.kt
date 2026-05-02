package ui

import interfaces.UserInterface
import java.awt.Component
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.UIManager
import kotlin.system.exitProcess
import models.FilePair

class UserInterfaceImpl : UserInterface {

    private val frame: JFrame = JFrame("Flac Lyric")

    init {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        frame.isUndecorated = true
        frame.setSize(0, 0)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    override fun showError(message: String) {
        JOptionPane.showMessageDialog(frame, message, "Erro", JOptionPane.ERROR_MESSAGE)
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
            JOptionPane.showMessageDialog(frame, scrollPane, title, messageType)
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
            JOptionPane.showMessageDialog(frame, "Está tudo no lugar!", "Informação", JOptionPane.INFORMATION_MESSAGE)
        }
    }

    override fun moveAndRename(filePairs: FilePair): Boolean {
        val result = JOptionPane.showConfirmDialog(
            frame,
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
            frame,
            "Arquivo\n${filePair.lyricFile.name}\nSerá Renomeado para\n${filePair.audioFile.nameWithoutExtension}.lrc",
            "Deseja continuar?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        if (result == JOptionPane.CLOSED_OPTION) {
            exitProcess(0)
        }

        return result == JOptionPane.YES_OPTION
    }

    override fun option(): Int {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        val label = JLabel("Escolha uma das opções abaixo:")
        label.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(label)
        panel.add(Box.createVerticalStrut(10))

        val buttonGroup = ButtonGroup()

        data class OptionMenu(val title: String, val hint: String)

        val options = listOf(
            OptionMenu(
                "Organizar Música e Lyrics",
                "Associa arquivos de áudio e letras (.lrc), movendo e renomeando as letras para a pasta do áudio correspondente."
            ),
            OptionMenu(
                "Listar nome dos arquivos sem .lrc",
                "Verifica na pasta de músicas quais arquivos não possuem um arquivo de letra correspondente."
            ),
            OptionMenu(
                "Listar arquivos .lrc sem sincronia",
                "Procura e lista os arquivos de letra que não possuem timestamps de sincronização."
            ),
            OptionMenu(
                "Remover 'V1:' de arquivos .lrc",
                "Limpa os arquivos de letra removendo a string 'V1:' que pode estar indevidamente inserida."
            ),
            OptionMenu(
                "Encontrar e Mover .lrc Isolados",
                "Procura arquivos .lrc que não possuem par correspondente (áudio), os move para uma pasta à sua escolha, deleta as pastas antigas vazias e cria um .txt listando-os."
            ),
            OptionMenu(
                "Verificar Estrutura de Nome",
                "Verifica se os arquivos FLAC estão no formato 'Artista - Música.extensão', validando com os metadados do arquivo ou via padrão de texto."
            )
        )

        val radioButtons = options.mapIndexed { index, (title, hint) ->
            val rb = JRadioButton("<html><b>${title}</b><br><small><font color='gray'>${hint}</font></small></html>")
            rb.alignmentX = Component.LEFT_ALIGNMENT
            if (index == 0) rb.isSelected = true
            buttonGroup.add(rb)
            panel.add(rb)
            panel.add(Box.createVerticalStrut(8))
            rb
        }

        val result = JOptionPane.showConfirmDialog(
            frame, panel, "Selecione a Ação", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )

        if (result != JOptionPane.OK_OPTION) {
            exitProcess(0)
        }

        return radioButtons.indexOfFirst { it.isSelected }
    }
}
