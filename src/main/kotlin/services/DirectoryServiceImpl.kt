package services

import interfaces.DirectoryService
import interfaces.FileService
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.system.exitProcess

class DirectoryServiceImpl(private val fileService: FileService = FileServiceImpl()) : DirectoryService {

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
                exitProcess(0)
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
                exitProcess(0)
            }
        }
    }
}
