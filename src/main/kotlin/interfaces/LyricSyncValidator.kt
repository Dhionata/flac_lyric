package interfaces

import java.io.File
import models.LyricValidationIssue

/**
 * Interface responsible for validating whether a lyric file (.lrc) is synchronized
 * and compatible with its corresponding audio file.
 */
interface LyricSyncValidator {
    /**
     * Inspects a lyric file and its optional matching audio file to detect anomalies,
     * such as absence of timestamps, static timestamps, mismatch with audio duration,
     * tags belonging to a different track/artist, or premature truncation.
     *
     * @param lyricFile The lyric (.lrc) file to validate.
     * @param audioFile The matching audio file, if available.
     * @return List of identified issues, or an empty list if the lyric is valid and synchronized.
     */
    fun validate(lyricFile: File, audioFile: File?): List<LyricValidationIssue>
}
