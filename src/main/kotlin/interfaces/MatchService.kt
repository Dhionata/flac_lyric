package interfaces

import java.io.File
import models.FilePair
import models.OperationResult

/**
 * Service responsible for correlating lyric files (.lrc) with their respective audio files.
 */
interface MatchService {
    /**
     * Maps and correlates the list of lyric files with the list of audio files, finding the corresponding pairs.
     *
     * @param lyricFiles List of found .lrc files.
     * @param audioFiles List of found audio files.
     * @return A list containing successfully mapped [FilePair] objects.
     */
    fun matchFiles(lyricFiles: List<File>, audioFiles: List<File>): List<FilePair>

    /**
     * Processes the list of found pairs, requesting user confirmation and moving/renaming the lyrics.
     *
     * @param filePairs The list of [FilePair] pairs to be processed.
     * @return [OperationResult] containing successes and failures of the operation.
     */
    fun handleFilePairs(filePairs: List<FilePair>): OperationResult
}
