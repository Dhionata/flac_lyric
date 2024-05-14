import interfaces.DirectoryService
import interfaces.FileService
import java.io.File
import java.nio.file.Paths
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.system.exitProcess

class DirectoryServiceImpl(private val fileService: FileService) : DirectoryService {

    init {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    }

    override fun getDirectory(dialogTitle: String): File {
        return JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            this.dialogTitle = dialogTitle
            this.currentDirectory = Paths.get(System.getProperty("user.home") + "\\Music").toFile()
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
