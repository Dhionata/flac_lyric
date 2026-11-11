package models

/**
 * Configuration flags controlling which normalization and cleanup rules are applied
 * to `.lrc` lyric files by [interfaces.LyricContentCleaner].
 *
 * @property removeSpeakerAndBgTags Removes `v1:`, `v2:`, `v3:` speaker markers and cleans `[bg:...]` background vocal tags.
 * @property removeInlineWordTimestamps Removes `<mm:ss.xx>` word-by-word karaoke tags inside verses.
 * @property removeCreditsAndWatermarks Removes embedded credit lines (`作词:`, `作曲:`, `Lyrics by:`) and URL/watermark lines.
 * @property sortAndDeduplicateMultiTimestamps Sorts out-of-order multi-timestamps on the same line and deduplicates identical tags.
 * @property removeUnsyncedLinesInSyncedFile Removes loose unsynchronized text lines when mixed inside an already synchronized `.lrc` file.
 * @property stripBom Removes leading UTF-8 Byte Order Mark (`\uFEFF`) characters.
 */
data class LyricCleanOptions(
    val removeSpeakerAndBgTags: Boolean = true,
    val removeInlineWordTimestamps: Boolean = true,
    val removeCreditsAndWatermarks: Boolean = true,
    val sortAndDeduplicateMultiTimestamps: Boolean = true,
    val removeUnsyncedLinesInSyncedFile: Boolean = false,
    val stripBom: Boolean = true,
)
