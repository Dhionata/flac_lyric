package ui

import exceptions.OperationCancelledException
import interfaces.UserInterface
import java.awt.Component
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import models.FilePair
import services.Messages

/**
 * Graphical implementation of [UserInterface] using Java Swing to display dialogs,
 * error messages, confirmation dialog boxes, progress bars, and the main menu.
 */
class UserInterfaceImpl : UserInterface {

    /** Hidden frame that serves as a parent to center JOptionPane dialog boxes. */
    private val frame: JFrame = JFrame("Flac Lyric")

    /** Active progress window. */
    private var progressDialog: JDialog? = null

    /** Active progress bar in Swing. */
    private var progressBar: JProgressBar? = null

    /** Descriptive text label for the current progress. */
    private var progressLabel: JLabel? = null

    init {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        frame.isUndecorated = true
        frame.setSize(0, 0)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    override fun showError(message: String) {
        JOptionPane.showMessageDialog(frame, message, Messages.get("dialog.error.title"), JOptionPane.ERROR_MESSAGE)
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
            showMessageDialog(movedMessage, Messages.get("dialog.result.info_title"), JOptionPane.INFORMATION_MESSAGE)
        }

        if (errorSet.isNotEmpty()) {
            val errorMessage = errorSet.joinToString("\n") { it.message ?: it.toString() }
            showMessageDialog(errorMessage, Messages.get("dialog.result.errors_title"), JOptionPane.ERROR_MESSAGE)
        }

        if (errorSet.isEmpty() && changedSet.isEmpty()) {
            JOptionPane.showMessageDialog(frame, Messages.get("dialog.result.all_in_place"), Messages.get("dialog.result.info_title"), JOptionPane.INFORMATION_MESSAGE)
        }
    }

    override fun moveAndRename(filePairs: FilePair): Boolean {
        val result = JOptionPane.showConfirmDialog(
            frame,
            Messages.get(
                "dialog.confirm.move_and_rename",
                filePairs.lyricFile.name,
                filePairs.lyricFile.parentFile,
                filePairs.audioFile.parentFile,
                filePairs.audioFile.nameWithoutExtension,
                filePairs.audioFile.name
            ),
            Messages.get("dialog.confirm.title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        if (result == JOptionPane.CLOSED_OPTION) {
            throw OperationCancelledException()
        }

        return result == JOptionPane.YES_OPTION
    }

    override fun onlyRename(filePair: FilePair): Boolean {
        val result = JOptionPane.showConfirmDialog(
            frame,
            Messages.get(
                "dialog.confirm.only_rename",
                filePair.lyricFile.name,
                filePair.audioFile.nameWithoutExtension
            ),
            Messages.get("dialog.confirm.title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        if (result == JOptionPane.CLOSED_OPTION) {
            throw OperationCancelledException()
        }

        return result == JOptionPane.YES_OPTION
    }

    override fun askToMoveIncorrectFiles(count: Int, txtFileName: String): Boolean {
        val result = JOptionPane.showConfirmDialog(
            frame,
            Messages.get("dialog.confirm.move_incorrect_msg", count, txtFileName),
            Messages.get("dialog.confirm.move_incorrect_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        return result == JOptionPane.YES_OPTION
    }

    override fun askToMoveFakeLossless(count: Int, txtFileName: String): Boolean {
        val result = JOptionPane.showConfirmDialog(
            frame,
            Messages.get("dialog.confirm.move_fake_msg", count, txtFileName),
            Messages.get("dialog.confirm.move_fake_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        return result == JOptionPane.YES_OPTION
    }

    override fun askToMoveUnmatchedLyrics(count: Int): Boolean {
        val result = JOptionPane.showConfirmDialog(
            frame,
            Messages.get("dialog.confirm.unmatched_lyrics_msg", count),
            Messages.get("dialog.confirm.unmatched_lyrics_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )
        if (result == JOptionPane.CLOSED_OPTION) {
            throw OperationCancelledException()
        }
        return result == JOptionPane.YES_OPTION
    }

    override fun askForAnalysisType(): Boolean {
        val options = arrayOf(
            Messages.get("dialog.confirm.analysis_type_option_quick"),
            Messages.get("dialog.confirm.analysis_type_option_full")
        )
        val result = JOptionPane.showOptionDialog(
            frame,
            Messages.get("dialog.confirm.analysis_type_msg"),
            Messages.get("dialog.confirm.analysis_type_title"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]
        )
        return result == 1 // 1 is "Full Analysis"
    }

    override fun showProgress(title: String, max: Int) {
        SwingUtilities.invokeLater {
            progressDialog = JDialog(frame, title, false).apply {
                val panel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = EmptyBorder(15, 15, 15, 15)
                }

                progressLabel = JLabel(Messages.get("dialog.progress.starting")).apply {
                    alignmentX = Component.CENTER_ALIGNMENT
                }

                progressBar = JProgressBar(0, max).apply {
                    isStringPainted = true
                    alignmentX = Component.CENTER_ALIGNMENT
                    preferredSize = Dimension(300, 25)
                }

                panel.add(progressLabel)
                panel.add(Box.createVerticalStrut(10))
                panel.add(progressBar)

                add(panel)
                pack()
                setLocationRelativeTo(frame)
                isVisible = true
            }
        }
    }

    override fun updateProgress(current: Int, text: String) {
        SwingUtilities.invokeLater {
            progressBar?.value = current
            progressLabel?.text = "<html><body style='width: 250px; text-align: center;'>$text</body></html>"
        }
    }

    override fun closeProgress() {
        SwingUtilities.invokeLater {
            progressDialog?.dispose()
            progressDialog = null
            progressBar = null
            progressLabel = null
        }
    }

    override fun option(): Int? {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        val label = JLabel(Messages.get("dialog.option.label"))
        label.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(label)
        panel.add(Box.createVerticalStrut(10))

        val buttonGroup = ButtonGroup()

        data class OptionMenu(val title: String, val hint: String)

        val options = listOf(
            OptionMenu(
                Messages.get("menu.option.0.title"),
                Messages.get("menu.option.0.hint")
            ),
            OptionMenu(
                Messages.get("menu.option.1.title"),
                Messages.get("menu.option.1.hint")
            ),
            OptionMenu(
                Messages.get("menu.option.2.title"),
                Messages.get("menu.option.2.hint")
            ),
            OptionMenu(
                Messages.get("menu.option.3.title"),
                Messages.get("menu.option.3.hint")
            ),
            OptionMenu(
                Messages.get("menu.option.4.title"),
                Messages.get("menu.option.4.hint")
            ),
            OptionMenu(
                Messages.get("menu.option.5.title"),
                Messages.get("menu.option.5.hint")
            ),
            OptionMenu(
                Messages.get("menu.option.6.title"),
                Messages.get("menu.option.6.hint")
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
            frame, panel, Messages.get("dialog.option.title"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )

        if (result != JOptionPane.OK_OPTION) {
            return null
        }

        return radioButtons.indexOfFirst { it.isSelected }
    }
}
