package services

import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.system.exitProcess

class DirectoryService(private val fileService: FileService) {

    init {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    }

    fun getDirectory(dialogTitle: String): File {
        return JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            this.dialogTitle = dialogTitle
        }.let { chooser ->
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile.apply {
                    fileService.printFilePermissions(this)
                }
            } else {
                exitProcess(0)
            }
        }
    }
}
