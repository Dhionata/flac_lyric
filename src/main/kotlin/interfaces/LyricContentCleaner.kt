package interfaces

import models.LyricCleanOptions
import models.LyricCleanResult

/**
 * Contract for inspecting and cleaning formatting clutter, extraneous tags, and
 * structural anomalies inside `.lrc` lyric content (Single Responsibility Principle).
 */
interface LyricContentCleaner {

    /**
     * Inspects a `.lrc` text and returns a list of short descriptions of cleanable dirty tags
     * present in the content (e.g. `v1:/v2:`, `[bg:]`, `<mm:ss>`, `créditos/URLs`).
     *
     * @param content Raw `.lrc` file content.
     * @return List of detected dirty pattern labels, or empty list if clean.
     */
    fun inspectDirtyPatterns(content: String): List<String>

    /**
     * Applies the specified [options] to clean and normalize a `.lrc` text.
     *
     * @param content Raw `.lrc` file content.
     * @param options Configuration of which cleanup rules to apply.
     * @return [LyricCleanResult] containing the cleaned text and summary of applied fixes.
     */
    fun clean(content: String, options: LyricCleanOptions = LyricCleanOptions()): LyricCleanResult
}
