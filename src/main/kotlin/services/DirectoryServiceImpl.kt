package services

import exceptions.OperationCancelledException
import interfaces.DirectoryService
import interfaces.FileService
import java.awt.Window
import java.io.File
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Implementation of [DirectoryService] that uses Swing's [JFileChooser] to select files and directories interactively.
 */
class DirectoryServiceImpl(private val fileService: FileService) : DirectoryService {

    /** Graphical dialog for file or directory selection, initialized lazily only when needed. */
    private val jFileChooser: JFileChooser by lazy {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {
        }
        JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
    }

    private fun showChooserDialog(dialogTitle: String, selectionMode: Int): File {
        val parentDialog = JDialog().apply {
            type = Window.Type.UTILITY
            isUndecorated = true
            isAlwaysOnTop = true
            setSize(0, 0)
            setLocationRelativeTo(null)
        }
        return try {
            jFileChooser.apply {
                this.dialogTitle = dialogTitle
                this.fileSelectionMode = selectionMode
            }.let { chooser ->
                if (chooser.showOpenDialog(parentDialog) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile.also {
                        fileService.printFilePermissions(it)
                    }
                } else {
                    throw OperationCancelledException()
                }
            }
        } finally {
            parentDialog.dispose()
        }
    }

    override fun getDirectory(dialogTitle: String): File {
        return showChooserDialog(dialogTitle, JFileChooser.DIRECTORIES_ONLY)
    }

    override fun getTxtFile(dialogTitle: String): File {
        return showChooserDialog(dialogTitle, JFileChooser.FILES_ONLY)
    }
}
