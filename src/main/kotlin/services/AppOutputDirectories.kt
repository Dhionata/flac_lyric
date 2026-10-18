package services

import java.io.File

/**
 * Centralizes the organized directory structure for all generated report files (.txt)
 * under the main `flac_lyric` folder with dedicated subfolders per function.
 */
object AppOutputDirectories {
    const val MAIN_DIR_NAME: String = "flac_lyric"

    const val SUBDIR_NO_LYRICS: String = "musicas_sem_letra"
    const val SUBDIR_UNSYNCED_LYRICS: String = "letras_sem_sincronia"
    const val SUBDIR_V1_LYRICS: String = "letras_com_v1"
    const val SUBDIR_ALONE_LYRICS: String = "letras_isoladas"
    const val SUBDIR_INCORRECT_NOMENCLATURE: String = "nomenclatura_incorreta"
    const val SUBDIR_FAKE_FLAC: String = "falsos_flac"
    const val SUBDIR_SYNC_REPORTS: String = "relatorios_sincronia"

    /**
     * Returns (and creates if needed) the subfolder for a specific function under `flac_lyric/<functionSubdir>`.
     */
    private fun getFunctionDirectory(functionSubdir: String, baseDir: File = File(".")): File {
        val dir = File(File(baseDir, MAIN_DIR_NAME), functionSubdir)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Returns a target [File] inside `flac_lyric/<functionSubdir>/<fileName>`, ensuring parent directories exist.
     */
    fun createReportFile(functionSubdir: String, fileName: String, baseDir: File = File(".")): File {
        val dir = getFunctionDirectory(functionSubdir, baseDir)
        return File(dir, fileName)
    }

    /**
     * Finds all `LyricsSyncIssues_*.txt` report files in `flac_lyric/relatorios_sincronia`
     * as well as any legacy reports in the root directory, sorted newest first.
     */
    fun findAllSyncIssueReports(baseDir: File = File(".")): List<File> {
        val results = mutableListOf<File>()
        val syncDir = File(File(baseDir, MAIN_DIR_NAME), SUBDIR_SYNC_REPORTS)
        if (syncDir.exists() && syncDir.isDirectory) {
            syncDir.listFiles { file ->
                file.isFile && file.name.startsWith("LyricsSyncIssues_") && file.name.endsWith(".txt")
            }?.let { results.addAll(it) }
        }

        baseDir.listFiles { file ->
            file.isFile && file.name.startsWith("LyricsSyncIssues_") && file.name.endsWith(".txt")
        }?.let { results.addAll(it) }

        return results
            .distinctBy { it.canonicalPath }
            .sortedByDescending { it.lastModified() }
    }

    /**
     * Resolves a report file path, checking the given path directly first and then `flac_lyric/relatorios_sincronia`.
     */
    fun resolveSyncReportFile(pathOrName: String, baseDir: File = File(".")): File {
        val direct = File(pathOrName)
        if (direct.exists()) return direct
        val inSyncDir = File(File(File(baseDir, MAIN_DIR_NAME), SUBDIR_SYNC_REPORTS), direct.name)
        if (inSyncDir.exists()) return inSyncDir
        return direct
    }
}
