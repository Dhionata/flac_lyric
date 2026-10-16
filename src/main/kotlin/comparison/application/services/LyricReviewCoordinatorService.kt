package comparison.application.services

import comparison.application.usecases.ExtractTrackDetailsUseCase
import comparison.application.usecases.FetchLrcLibLyricsUseCase
import comparison.application.usecases.ParseIssuesReportUseCase
import comparison.application.usecases.ParseLyricLinesUseCase
import comparison.application.usecases.SaveLyricChoiceUseCase
import comparison.domain.ports.AudioMetadataExtractor
import comparison.domain.ports.LrcLibClient
import comparison.domain.ports.LyricFileStorage
import comparison.domain.ports.LyricLineParser
import comparison.domain.ports.LyricsReportParser
import comparison.domain.ports.WebLyricReviewServer
import comparison.infrastructure.audio.AudioMetadataExtractorImpl
import comparison.infrastructure.clients.CompositeLyricsClientImpl
import comparison.infrastructure.io.LyricFileStorageImpl
import comparison.infrastructure.parsers.LyricLineParserImpl
import comparison.infrastructure.parsers.LyricsReportParserImpl
import comparison.infrastructure.services.WebActionRunnerService
import comparison.infrastructure.web.WebLyricReviewServerImpl
import comparison.infrastructure.web.WebReviewRouter
import java.awt.Desktop
import java.net.URI

/**
 * High-level application service that coordinates the launch and lifecycle
 * of the lyric review and comparison web subsystem.
 */
class LyricReviewCoordinatorService(
    private val reportParser: LyricsReportParser = LyricsReportParserImpl(),
    private val audioMetadataExtractor: AudioMetadataExtractor = AudioMetadataExtractorImpl(),
    private val lrcLibClient: LrcLibClient = CompositeLyricsClientImpl(),
    private val lyricStorage: LyricFileStorage = LyricFileStorageImpl(),
    private val lineParser: LyricLineParser = LyricLineParserImpl(),
    private val actionRunner: WebActionRunnerService = WebActionRunnerService()
) {

    private val parseReportUseCase = ParseIssuesReportUseCase(reportParser)
    private val fetchLrcLibUseCase = FetchLrcLibLyricsUseCase(lrcLibClient)
    private val parseLinesUseCase = ParseLyricLinesUseCase(lineParser)
    private val extractDetailsUseCase = ExtractTrackDetailsUseCase(
        metadataExtractor = audioMetadataExtractor,
        lyricStorage = lyricStorage,
        parseLinesUseCase = parseLinesUseCase,
        fetchLrcLibUseCase = fetchLrcLibUseCase
    )
    private val saveLyricUseCase = SaveLyricChoiceUseCase(lyricStorage)

    private val router = WebReviewRouter(
        parseReportUseCase = parseReportUseCase,
        extractDetailsUseCase = extractDetailsUseCase,
        saveLyricUseCase = saveLyricUseCase,
        fetchLrcLibUseCase = fetchLrcLibUseCase,
        audioMetadataExtractor = audioMetadataExtractor,
        actionRunner = actionRunner
    )

    private val server: WebLyricReviewServer = WebLyricReviewServerImpl(router)

    /**
     * Starts the web review server and opens the default web browser.
     *
     * @param preferredPort Port number to attempt binding to.
     * @param openBrowser Whether to automatically launch the system web browser.
     * @return The URL of the started review web interface.
     */
    fun startReview(preferredPort: Int = 8080, openBrowser: Boolean = true): String {
        val port = server.start(preferredPort)
        val url = "http://localhost:$port"

        if (openBrowser && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(URI.create(url))
            } catch (e: Exception) {
                println("Aviso: Não foi possível abrir o navegador automaticamente: ${e.message}")
            }
        }

        return url
    }

    /**
     * Stops the review server.
     */
    fun stopReview() {
        server.stop()
    }

    val isRunning: Boolean
        get() = server.isRunning

    val serverUrl: String?
        get() = server.serverUrl
}
