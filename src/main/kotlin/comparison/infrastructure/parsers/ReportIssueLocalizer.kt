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
        "LETRA_ISOLADA" to "lyric.issue.type.orphan_lyric",
        "ORPHAN_LYRIC" to "lyric.issue.type.orphan_lyric",
        "ERRO_LEITURA" to "lyric.issue.type.read_error",
        "READ_ERROR" to "lyric.issue.type.read_error",
        "ERRO_CODIFICACAO" to "lyric.issue.type.encoding_error",
        "ENCODING_ERROR" to "lyric.issue.type.encoding_error",
        "SEM_TIMESTAMPS" to "lyric.issue.type.no_timestamps",
        "NO_TIMESTAMPS" to "lyric.issue.type.no_timestamps",
        "SINCRONIA_INCOMPLETA" to "lyric.issue.type.partial_sync",
        "PARTIAL_SYNC" to "lyric.issue.type.partial_sync",
        "TIMESTAMPS_FORA_DE_ORDEM" to "lyric.issue.type.out_of_order",
        "OUT_OF_ORDER" to "lyric.issue.type.out_of_order",
        "TIMESTAMPS_ESTATICOS" to "lyric.issue.type.static_timestamps",
        "STATIC_TIMESTAMPS" to "lyric.issue.type.static_timestamps",
        "TIMESTAMPS_DUPLICADOS" to "lyric.issue.type.duplicate_timestamps",
        "DUPLICATE_TIMESTAMPS" to "lyric.issue.type.duplicate_timestamps",
        "TAGS_SUJAS" to "lyric.issue.type.dirty_tags",
        "DIRTY_TAGS" to "lyric.issue.type.dirty_tags",
        "MUSICA_DIFERENTE" to "lyric.issue.type.wrong_song",
        "DIFFERENT_SONG" to "lyric.issue.type.wrong_song",
        "ULTRAPASSOU_DURACAO" to "lyric.issue.type.exceeds_duration",
        "EXCEEDS_DURATION" to "lyric.issue.type.exceeds_duration",
        "TAG_LENGTH_DIVERGENTE" to "lyric.issue.type.length_mismatch",
        "LENGTH_MISMATCH" to "lyric.issue.type.length_mismatch",
        "TERMINO_PREMATURO" to "lyric.issue.type.premature_end",
        "PREMATURE_END" to "lyric.issue.type.premature_end",
    )

    private val encodingPtRegex = Regex(
        """^O arquivo (?:apresenta|contém) caracteres corrompidos por codificação inválida \(ex:\s*linha (\d+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val encodingEnRegex = Regex(
        """^The file contains corrupted characters due to invalid encoding \(e\.g\.\s*line (\d+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )

    private val partialSyncPtRegex = Regex(
        """^O arquivo possui (\d+) linha\(s\) de texto sem timestamp misturadas?(?:\(s\))? com versos sincronizados \(primeira na linha (\d+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val partialSyncEnRegex = Regex(
        """^The file has (\d+) text line\(s\) without timestamp mixed with synchronized verses \(first at line (\d+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )

    private val duplicateTsPtRegex = Regex(
        """^(?:Foram )?encontrados (\d+) versos (?:empilhados exatamente na mesma minutagem|acumulados no mesmo timestamp) \(([^)]+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val duplicateTsEnRegex = Regex(
        """^Found (\d+) verses stacked at the exact same timestamp \(([^)]+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )

    private val dirtyTagsPtRegex = Regex(
        """^(?:O arquivo de letra|A letra) contém marcações extras que podem ser limpas \((.+)\)\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val dirtyTagsEnRegex = Regex(
        """^The lyric file contains extraneous tags that can be cleaned \((.+)\)\.?$""",
        RegexOption.IGNORE_CASE
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
        if (mappedTagKey == "lyric.issue.type.orphan_lyric") {
            return mappedTagKey to Messages.get("lyric.issue.orphan_lyric")
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
            desc.equals("O arquivo .lrc está isolado na pasta sem um arquivo de áudio correspondente.", ignoreCase = true) ||
            desc.equals("The .lrc file is isolated in the folder without a corresponding audio file.", ignoreCase = true)
        ) {
            return "lyric.issue.type.orphan_lyric" to Messages.get("lyric.issue.orphan_lyric")
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
        (encodingPtRegex.find(desc) ?: encodingEnRegex.find(desc))?.let { m ->
            val lineNum = m.groupValues[1].toIntOrNull() ?: 1
            return "lyric.issue.type.encoding_error" to Messages.get("lyric.issue.encoding_error", lineNum)
        }

        (partialSyncPtRegex.find(desc) ?: partialSyncEnRegex.find(desc))?.let { m ->
            val count = m.groupValues[1].toIntOrNull() ?: 1
            val firstLine = m.groupValues[2].toIntOrNull() ?: 1
            return "lyric.issue.type.partial_sync" to Messages.get("lyric.issue.partial_sync", count, firstLine)
        }

        (duplicateTsPtRegex.find(desc) ?: duplicateTsEnRegex.find(desc))?.let { m ->
            val count = m.groupValues[1].toIntOrNull() ?: 2
            val ts = m.groupValues[2]
            return "lyric.issue.type.duplicate_timestamps" to Messages.get("lyric.issue.duplicate_timestamps", count, ts)
        }

        (dirtyTagsPtRegex.find(desc) ?: dirtyTagsEnRegex.find(desc))?.let { m ->
            val tags = m.groupValues[1]
            return "lyric.issue.type.dirty_tags" to Messages.get("lyric.issue.dirty_tags", tags)
        }

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
