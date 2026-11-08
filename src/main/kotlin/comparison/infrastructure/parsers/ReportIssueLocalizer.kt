package comparison.infrastructure.parsers

import services.Messages

/**
 * Contextualizes/translates issue strings parsed from sync report `.txt` files
 * (including reports previously generated in another language or with legacy Portuguese tags)
 * so that both `[TAG]` codes and issue descriptions match the active system locale ([Messages]).
 */
object ReportIssueLocalizer {

    private val leadingTagRegex = Regex("""^\[([A-Za-z0-9_]+)\]:?\s*(.*)$""")

    private val tagToTypeKey: Map<String, String> = mapOf(
        "SEM_LETRA" to "lyric.issue.type.missing_lyric",
        "MISSING_LYRICS" to "lyric.issue.type.missing_lyric",
        "ERRO_LEITURA" to "lyric.issue.type.read_error",
        "READ_ERROR" to "lyric.issue.type.read_error",
        "SEM_TIMESTAMPS" to "lyric.issue.type.no_timestamps",
        "NO_TIMESTAMPS" to "lyric.issue.type.no_timestamps",
        "TIMESTAMPS_FORA_DE_ORDEM" to "lyric.issue.type.out_of_order",
        "OUT_OF_ORDER" to "lyric.issue.type.out_of_order",
        "TIMESTAMPS_ESTATICOS" to "lyric.issue.type.static_timestamps",
        "STATIC_TIMESTAMPS" to "lyric.issue.type.static_timestamps",
        "MUSICA_DIFERENTE" to "lyric.issue.type.wrong_song",
        "DIFFERENT_SONG" to "lyric.issue.type.wrong_song",
        "ULTRAPASSOU_DURACAO" to "lyric.issue.type.exceeds_duration",
        "EXCEEDS_DURATION" to "lyric.issue.type.exceeds_duration",
        "TAG_LENGTH_DIVERGENTE" to "lyric.issue.type.length_mismatch",
        "LENGTH_MISMATCH" to "lyric.issue.type.length_mismatch",
        "TERMINO_PREMATURO" to "lyric.issue.type.premature_end",
        "PREMATURE_END" to "lyric.issue.type.premature_end",
    )

    private val outOfOrderPtRegex = Regex(
        """^Verso na linha (\d+) possui minutagem \(([^)]+)\) inferior à linha anterior \(([^)]+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val outOfOrderEnRegex = Regex(
        """^Line (\d+) has a timestamp \(([^)]+)\) lower than the previous line \(([^)]+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )

    private val staticPtRegex = Regex(
        """^Todos os timestamps são estáticos/idênticos \(([^)]+)\)\s*-\s*letra sem sincronização real\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val staticEnRegex = Regex(
        """^All timestamps are static/identical \(([^)]+)\)\s*-\s*lyrics have no real synchronization\.?$""",
        RegexOption.IGNORE_CASE
    )

    private val wrongSongPtRegex = Regex(
        """^A letra possui metadados de outra música \((.+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val wrongSongEnRegex = Regex(
        """^The lyric file contains metadata from a different song \((.+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val artistMetaRegex = Regex("""(?:artista|artist):\s*'([^']*)'""", RegexOption.IGNORE_CASE)
    private val titleMetaRegex = Regex("""(?:título|titulo|title):\s*'([^']*)'""", RegexOption.IGNORE_CASE)

    private val exceedsPtRegex = Regex(
        """^Timestamps ultrapassam o término do áudio \(último verso em ([^\s)]+) para faixa de ([^)]+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val exceedsEnRegex = Regex(
        """^Timestamps exceed audio duration \(last line at ([^\s)]+) for an audio track of ([^)]+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )

    private val lengthMismatchPtRegex = Regex(
        """^A tag de duração do LRC \[length:\s*([^\]]+)\] diverge em (\d+)s da duração da faixa \(([^)]+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val lengthMismatchEnRegex = Regex(
        """^The LRC \[length:\s*([^\]]+)\] tag differs by (\d+)s from audio track duration \(([^)]+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )

    private val prematurePtRegex = Regex(
        """^Letra encerra prematuramente no verso em (\S+) para uma faixa de ([^).]+)\)?\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val prematureEnRegex = Regex(
        """^Lyrics end prematurely at line (\S+) for an audio track of ([^).]+)\)?\.?$""",
        RegexOption.IGNORE_CASE
    )

    fun localizeIssues(issues: List<String>): List<String> = issues.map { localizeIssue(it) }

    fun localizeIssue(rawIssue: String): String {
        val trimmed = rawIssue.trim()
        if (trimmed.isEmpty()) return rawIssue

        val tagMatch = leadingTagRegex.find(trimmed)
        val rawTag = tagMatch?.groupValues?.get(1)?.uppercase()
        val rawDesc = if (tagMatch != null) tagMatch.groupValues[2].trim() else trimmed

        val (inferredTypeKey, localizedDesc) = localizeDescriptionAndInferType(rawTag, rawDesc)
        val typeKey = (rawTag?.let { tagToTypeKey[it] }) ?: inferredTypeKey
        val localizedTag = when {
            typeKey != null -> Messages.get(typeKey)
            rawTag != null -> rawTag
            else -> null
        }

        return if (localizedTag != null) {
            if (localizedDesc.isNotEmpty()) "[$localizedTag] $localizedDesc" else "[$localizedTag]"
        } else {
            localizedDesc
        }
    }

    private fun localizeDescriptionAndInferType(rawTag: String?, desc: String): Pair<String?, String> {
        val mappedTagKey = rawTag?.let { tagToTypeKey[it] }
        if (mappedTagKey == "lyric.issue.type.missing_lyric") {
            return mappedTagKey to Messages.get("lyric.issue.missing_lyric")
        }
        if (mappedTagKey == "lyric.issue.type.read_error") {
            return mappedTagKey to Messages.get("lyric.issue.read_error")
        }
        if (mappedTagKey == "lyric.issue.type.no_timestamps") {
            return mappedTagKey to Messages.get("lyric.issue.no_timestamps")
        }

        if (desc.isEmpty()) {
            return null to ""
        }

        // Fixed messages when no leading [TAG] was present
        if (
            desc.equals("A música não possui arquivo de letra (.lrc) correspondente.", ignoreCase = true) ||
            desc.equals("The audio track does not have a corresponding (.lrc) lyric file.", ignoreCase = true)
        ) {
            return "lyric.issue.type.missing_lyric" to Messages.get("lyric.issue.missing_lyric")
        }

        if (
            desc.equals("Não foi possível ler o conteúdo do arquivo .lrc.", ignoreCase = true) ||
            desc.equals("Could not read .lrc file content.", ignoreCase = true)
        ) {
            return "lyric.issue.type.read_error" to Messages.get("lyric.issue.read_error")
        }

        if (
            desc.equals("O arquivo não possui marcações de timestamp [mm:ss] (letra não sincronizada).", ignoreCase = true) ||
            desc.equals("The file has no [mm:ss] timestamps (unsynchronized lyrics).", ignoreCase = true)
        ) {
            return "lyric.issue.type.no_timestamps" to Messages.get("lyric.issue.no_timestamps")
        }

        // Parameterized messages
        (outOfOrderPtRegex.find(desc) ?: outOfOrderEnRegex.find(desc))?.let { m ->
            val lineNum = m.groupValues[1].toIntOrNull() ?: 1
            val currTs = m.groupValues[2]
            val prevTs = m.groupValues[3]
            return "lyric.issue.type.out_of_order" to Messages.get("lyric.issue.out_of_order", lineNum, currTs, prevTs)
        }

        (staticPtRegex.find(desc) ?: staticEnRegex.find(desc))?.let { m ->
            val ts = m.groupValues[1]
            return "lyric.issue.type.static_timestamps" to Messages.get("lyric.issue.static_timestamps", ts)
        }

        (wrongSongPtRegex.find(desc) ?: wrongSongEnRegex.find(desc))?.let { m ->
            val rawMeta = m.groupValues[1]
            val artistVal = artistMetaRegex.find(rawMeta)?.groupValues?.get(1)
            val titleVal = titleMetaRegex.find(rawMeta)?.groupValues?.get(1)
            val localizedMeta = if (artistVal != null || titleVal != null) {
                listOfNotNull(
                    artistVal?.let { Messages.get("lyric.issue.meta.artist", it) },
                    titleVal?.let { Messages.get("lyric.issue.meta.title", it) }
                ).joinToString(", ")
            } else {
                rawMeta
            }
            return "lyric.issue.type.wrong_song" to Messages.get("lyric.issue.wrong_song", localizedMeta)
        }

        (exceedsPtRegex.find(desc) ?: exceedsEnRegex.find(desc))?.let { m ->
            val lastTs = m.groupValues[1]
            val audioDur = m.groupValues[2]
            return "lyric.issue.type.exceeds_duration" to Messages.get("lyric.issue.exceeds_duration", lastTs, audioDur)
        }

        (lengthMismatchPtRegex.find(desc) ?: lengthMismatchEnRegex.find(desc))?.let { m ->
            val lrcLen = m.groupValues[1]
            val diffSec = m.groupValues[2].toIntOrNull() ?: 0
            val audioDur = m.groupValues[3]
            return "lyric.issue.type.length_mismatch" to Messages.get("lyric.issue.length_mismatch", lrcLen, diffSec, audioDur)
        }

        (prematurePtRegex.find(desc) ?: prematureEnRegex.find(desc))?.let { m ->
            val lastTs = m.groupValues[1]
            val audioDur = m.groupValues[2].trim()
            return "lyric.issue.type.premature_end" to Messages.get("lyric.issue.premature_end", lastTs, audioDur)
        }

        return null to desc
    }
}
