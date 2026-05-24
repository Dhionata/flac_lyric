package services

import exceptions.OperationCancelledException
import interfaces.DirectoryService
import interfaces.FileService
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Implementation of [DirectoryService] that uses Swing's [JFileChooser] to select files and directories interactively.
 */
class DirectoryServiceImpl(private val fileService: FileService = FileServiceImpl()) : DirectoryService {

    /** Graphical dialog for file or directory selection. */
    private val jFileChooser: JFileChooser = JFileChooser()

    init {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        jFileChooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }

    override fun getDirectory(dialogTitle: String): File {
        return jFileChooser.apply { this.dialogTitle = dialogTitle }.let { chooser ->
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile.also {
                    fileService.printFilePermissions(it)
                }
            } else {
                throw OperationCancelledException()
            }
        }
    }

    override fun getTxtFile(dialogTitle: String): File {
        return jFileChooser.apply {
            this.dialogTitle = dialogTitle
            fileSelectionMode = JFileChooser.FILES_ONLY
        }.let { chooser ->
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile.also {
                    fileService.printFilePermissions(it)
                }
            } else {
                throw OperationCancelledException()
            }
        }
    }
}
