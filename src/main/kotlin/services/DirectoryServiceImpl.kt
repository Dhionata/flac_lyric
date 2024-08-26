package services

import interfaces.DirectoryService
import interfaces.FileService
import java.io.File
import java.nio.file.Paths
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.system.exitProcess

class DirectoryServiceImpl(private val fileService: FileService = FileServiceImpl()) : DirectoryService {

    private val jFileChooser: JFileChooser

    init {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        jFileChooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            currentDirectory = Paths.get(System.getProperty("user.home") + "\\Music").toFile()
        }
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
}
