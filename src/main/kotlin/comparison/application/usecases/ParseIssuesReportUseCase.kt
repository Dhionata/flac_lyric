package comparison.application.usecases

import comparison.domain.models.ReportIssueItem
import comparison.domain.ports.LyricsReportParser
import java.io.File

/**
 * Use case to parse an issues report file into actionable items.
 */
class ParseIssuesReportUseCase(
    private val reportParser: LyricsReportParser
) {
    fun execute(reportFile: File): List<ReportIssueItem> {
        return reportParser.parseReport(reportFile)
    }
}
