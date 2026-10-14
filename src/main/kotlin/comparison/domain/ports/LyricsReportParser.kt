package comparison.domain.ports

import comparison.domain.models.ReportIssueItem
import java.io.File

/**
 * Port for parsing a sync issues report file into domain items.
 */
interface LyricsReportParser {
    /**
     * Parses the specified report file into a list of [ReportIssueItem].
     *
     * @param reportFile The report file to parse.
     * @return List of parsed issue entries.
     */
    fun parseReport(reportFile: File): List<ReportIssueItem>
}
