package comparison.infrastructure.web

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import comparison.application.usecases.ExtractTrackDetailsUseCase
import comparison.application.usecases.FetchLrcLibLyricsUseCase
import comparison.application.usecases.OpenFileLocationUseCase
import comparison.application.usecases.ParseIssuesReportUseCase
import comparison.application.usecases.ParseLyricLinesUseCase
import comparison.application.usecases.SaveLyricChoiceUseCase
import comparison.domain.models.ActionExecutionRequest
import comparison.domain.models.ReportIssueItem
import comparison.domain.models.SaveLyricRequest
import comparison.domain.ports.AudioMetadataExtractor
import comparison.infrastructure.io.SystemFileOpenerImpl
import comparison.infrastructure.parsers.LyricLineParserImpl
import comparison.infrastructure.parsers.ReportIssueLocalizer
import comparison.infrastructure.services.WebActionRunnerService
import interfaces.AuditLogService
import interfaces.LyricContentCleaner
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import models.LyricCleanOptions
import services.AuditLogServiceImpl
import services.AuditRunContext
import services.EmbeddedCleanupOutcome
import services.EmbeddedLyricsHelper
import services.LoggingInitializer
import services.LyricContentCleanerImpl
import services.Messages

/**
 * HTTP Router and request dispatcher for the lyric comparison and web application interface.
 */
class WebReviewRouter(
    private val parseReportUseCase: ParseIssuesReportUseCase,
    private val extractDetailsUseCase: ExtractTrackDetailsUseCase,
    private val saveLyricUseCase: SaveLyricChoiceUseCase,
    private val fetchLrcLibUseCase: FetchLrcLibLyricsUseCase,
    private val audioMetadataExtractor: AudioMetadataExtractor,
    private val openFileLocationUseCase: OpenFileLocationUseCase = OpenFileLocationUseCase(SystemFileOpenerImpl()),
    private val parseLinesUseCase: ParseLyricLinesUseCase = ParseLyricLinesUseCase(LyricLineParserImpl()),
    private val lyricContentCleaner: LyricContentCleaner = LyricContentCleanerImpl(),
    private val audioStreamer: HttpAudioStreamer = HttpAudioStreamer(),
    private val actionRunner: WebActionRunnerService = WebActionRunnerService(),
    private val gson: Gson = Gson(),
    private val shutdownGracePeriodMs: Long = 1500L,
    private val onShutdownRequested: (() -> Unit)? = null,
    private val auditLogService: AuditLogService = AuditLogServiceImpl(),
) : HttpHandler {

    private val cachedItems = mutableMapOf<Int, ReportIssueItem>()
    private val activeClients = ConcurrentHashMap<String, Long>()
    private val lastActivityMs = AtomicLong(System.currentTimeMillis())
    private val shutdownScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "web-shutdown-scheduler").apply { isDaemon = true }
    }
    private val shutdownLock = Any()
    private var pendingShutdownFuture: ScheduledFuture<*>? = null

    private fun recordClientActivity(clientId: String? = null) {
        val now = System.currentTimeMillis()
        lastActivityMs.set(now)
        if (!clientId.isNullOrBlank()) {
            activeClients[clientId] = now
        }
        synchronized(shutdownLock) {
            pendingShutdownFuture?.cancel(false)
            pendingShutdownFuture = null
        }
    }

    override fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod

        if (path != "/api/shutdown") {
            recordClientActivity()
        }

        try {
            when (path) {
                "/" if method == "GET" -> handleIndex(exchange)
                "/api/reports" if method == "GET" -> handleListReports(exchange)
                "/api/items" if method == "GET" -> handleGetItems(exchange)
                "/api/track" if method == "GET" -> handleGetTrack(exchange)
                "/api/audio" if method == "GET" -> handleStreamAudio(exchange)
                "/api/artwork" if method == "GET" -> handleGetArtwork(exchange)
                "/api/save" if method == "POST" -> handleSaveLyric(exchange)
                "/api/instrumental" if method == "POST" -> handleGenerateInstrumental(exchange)
                "/api/save-best" if method == "POST" -> handleSaveBestForTrack(exchange)
                "/api/batch-save-best" if method == "POST" -> handleBatchSaveBest(exchange)
                "/api/search" if method == "GET" -> handleSearchRemote(exchange)
                "/api/open-location" if method == "GET" -> handleOpenLocation(exchange)
                "/api/parse-lrc" if method == "POST" -> handleParseLrc(exchange)
                "/api/clean-lrc" if method == "POST" -> handleCleanLrc(exchange)
                "/api/clean-embedded-lyrics" if method == "POST" -> handleCleanEmbeddedLyrics(exchange)
                "/api/menu-options" if method == "GET" -> handleMenuOptions(exchange)
                "/api/action/execute" if method == "POST" -> handleActionExecute(exchange)
                "/api/action/status" if method == "GET" -> handleActionStatus(exchange)
                "/api/action/browse-dir" if method == "GET" -> handleActionBrowseDir(exchange)
                "/api/action/config" if method == "GET" -> handleActionConfig(exchange)
                "/api/locale" if method == "GET" -> handleLocale(exchange)
                "/api/locale" if method == "POST" -> handleSetLocale(exchange)
                "/api/logs/events" if method == "GET" -> handleGetLogEvents(exchange)
                "/api/logs/files" if method == "GET" -> handleGetLogFiles(exchange)
                "/api/logs/file" if method == "GET" -> handleGetLogFileContent(exchange)
                "/api/heartbeat" if (method == "POST" || method == "GET") -> handleHeartbeat(exchange)
                "/api/shutdown" if (method == "POST" || method == "GET") -> handleShutdown(exchange)
                else -> sendJsonResponse(exchange, 404, mapOf("error" to Messages.get("error.endpoint_not_found", path)))
            }
        } catch (e: Exception) {
            sendJsonResponse(exchange, 500, mapOf("error" to (e.message ?: e.toString())))
        }
    }

    private fun buildLocalePayload(): Map<String, Any?> {
        val activeLocale = Messages.getLocale()
        val lang = Messages.toCanonicalTag(activeLocale)
        return mapOf(
            "locale" to lang,
            "systemLocale" to Messages.toCanonicalTag(Messages.systemLocale),
            "isAuto" to Messages.isUsingSystemLocale(),
            "isPortuguese" to lang.lowercase().startsWith("pt"),
            "availableLocales" to Messages.getAvailableLocales(),
            "messages" to Messages.getAllWithFallback()
        )
    }

    private fun handleLocale(exchange: HttpExchange) {
        sendJsonResponse(exchange, 200, buildLocalePayload())
    }

    private fun handleSetLocale(exchange: HttpExchange) {
        val bodyMap = parseJsonBodyMap(exchange)
        val requestedTag = bodyMap["locale"] as? String
        Messages.setLocaleByTag(requestedTag)
        sendJsonResponse(exchange, 200, buildLocalePayload())
    }

    private fun handleGetLogEvents(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val category = queryParams["category"]
        val actionId = queryParams["actionId"]?.toIntOrNull()
        val runId = queryParams["runId"]
        val search = queryParams["q"]
        val limit = queryParams["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 150
        val offset = queryParams["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val result = LoggingInitializer.queryStructuredEvents(
            category = category,
            actionId = actionId,
            runId = runId,
            search = search,
            limit = limit,
            offset = offset
        )

        val enrichedEvents = result.events.map { ev ->
            val localizedActionTitle = ev.actionKey?.let { key ->
                Messages.get(key).takeIf { it != key }
            } ?: ev.actionFallback
            val catKey = "web.logs.cat.${ev.category.lowercase()}"
            val localizedCategory = Messages.get(catKey).takeIf { it != catKey } ?: ev.category

            mapOf(
                "id" to ev.id,
                "runId" to ev.runId,
                "epochMs" to ev.epochMs,
                "timestamp" to ev.timestamp,
                "category" to ev.category,
                "localizedCategory" to localizedCategory,
                "eventType" to ev.eventType,
                "actionId" to ev.actionId,
                "actionKey" to ev.actionKey,
                "actionFallback" to ev.actionFallback,
                "localizedActionTitle" to localizedActionTitle,
                "renderedMessage" to LoggingInitializer.renderEventMessage(ev),
                "targetPath" to ev.targetPath,
                "targetName" to ev.targetName,
                "beforePath" to ev.beforePath,
                "afterPath" to ev.afterPath,
                "summaryStats" to ev.summaryStats,
                "diffLines" to ev.diffLines,
                "metadata" to ev.metadata
            )
        }

        val enrichedRuns = result.runs.map { r ->
            val localizedActionTitle = r.actionKey?.let { key ->
                Messages.get(key).takeIf { it != key }
            } ?: r.actionFallback
            mapOf(
                "runId" to r.runId,
                "actionId" to r.actionId,
                "actionKey" to r.actionKey,
                "actionFallback" to r.actionFallback,
                "localizedActionTitle" to localizedActionTitle,
                "startedAt" to r.startedAt,
                "epochMs" to r.epochMs,
                "eventCount" to r.eventCount,
                "errorCount" to r.errorCount
            )
        }

        sendJsonResponse(
            exchange,
            200,
            mapOf(
                "events" to enrichedEvents,
                "totalMatching" to result.totalMatching,
                "totalEvents" to result.totalEvents,
                "countsByCategory" to result.countsByCategory,
                "runs" to enrichedRuns,
                "logsDir" to LoggingInitializer.getLogsDirectory().canonicalPath
            )
        )
    }

    private fun handleGetLogFiles(exchange: HttpExchange) {
        val files = LoggingInitializer.listLogFiles()
        sendJsonResponse(
            exchange,
            200,
            mapOf(
                "logsDir" to LoggingInitializer.getLogsDirectory().canonicalPath,
                "files" to files
            )
        )
    }

    private fun handleGetLogFileContent(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val fileName = queryParams["name"].orEmpty()
        val tailLines = queryParams["tail"]?.toIntOrNull() ?: 400
        val search = queryParams["q"]

        val result = LoggingInitializer.readLogFileTail(
            fileName = fileName,
            tailLines = tailLines,
            search = search
        )
        if (result == null) {
            sendJsonResponse(exchange, 404, mapOf("error" to Messages.get("error.file_not_found", fileName)))
            return
        }
        sendJsonResponse(exchange, 200, result)
    }

    private fun handleIndex(exchange: HttpExchange) {
        val lang = Messages.toCanonicalTag(Messages.getLocale())
        val messagesJson = gson.toJson(Messages.getAllWithFallback()).replace("</", "<\\/")
        val localeJson = gson.toJson(lang)
        val localeMetaJson = gson.toJson(
            mapOf(
                "locale" to lang,
                "systemLocale" to Messages.toCanonicalTag(Messages.systemLocale),
                "isAuto" to Messages.isUsingSystemLocale(),
                "availableLocales" to Messages.getAvailableLocales()
            )
        ).replace("</", "<\\/")
        val i18nBootstrapScript =
            "<script id=\"server-i18n\">window.__SERVER_LOCALE__ = $localeJson; window.__SERVER_LOCALE_META__ = $localeMetaJson; window.__SERVER_MESSAGES__ = $messagesJson;</script>"
        val localizedHtml = WebStaticAssets.INDEX_HTML
            .replaceFirst("lang=\"pt-BR\"", "lang=\"$lang\"")
            .replaceFirst("</head>", "$i18nBootstrapScript\n</head>")
        val htmlBytes = localizedHtml.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, htmlBytes.size.toLong())
        exchange.responseBody.use { it.write(htmlBytes) }
    }

    private fun handleMenuOptions(exchange: HttpExchange) {
        val options = (0..8).map { index ->
            mapOf(
                "id" to index, "title" to Messages.get("menu.option.$index.title"), "hint" to Messages.get("menu.option.$index.hint"), "isComparator" to (index == 8)
            )
        }
        sendJsonResponse(exchange, 200, options)
    }

    private fun handleActionConfig(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val actionId = queryParams["id"]?.toIntOrNull() ?: 0

        val config = when (actionId) {
            0 -> mapOf(
                "id" to 0,
                "title" to Messages.get("menu.option.0.title"),
                "hint" to Messages.get("menu.option.0.hint"),
                "description" to Messages.get("action.0.description"),
                "inputs" to listOf(
                    mapOf(
                        "key" to "musicDir",
                        "label" to Messages.get("prompt.select.music_dir"),
                        "required" to true,
                        "placeholder" to Messages.get("action.placeholder.music_dir")
                    ),
                    mapOf(
                        "key" to "lyricsDir",
                        "label" to Messages.get("prompt.select.lyrics_dir"),
                        "required" to true,
                        "placeholder" to Messages.get("action.placeholder.lyrics_dir")
                    )
                ),
                "options" to listOf(
                    mapOf("key" to "moveFiles", "label" to Messages.get("action.0.opt.move_files"), "type" to "checkbox", "default" to true),
                    mapOf("key" to "renameFiles", "label" to Messages.get("action.0.opt.rename_files"), "type" to "checkbox", "default" to true)
                )
            )

            1 -> mapOf(
                "id" to 1,
                "title" to Messages.get("menu.option.1.title"),
                "hint" to Messages.get("menu.option.1.hint"),
                "description" to Messages.get("action.1.description"),
                "inputs" to listOf(
                    mapOf(
                        "key" to "musicDir",
                        "label" to Messages.get("prompt.select.music_dir"),
                        "required" to true,
                        "placeholder" to Messages.get("action.placeholder.music_dir")
                    )
                ),
                "options" to emptyList<Any>()
            )

            2 -> mapOf(
                "id" to 2,
                "title" to Messages.get("menu.option.2.title"),
                "hint" to Messages.get("menu.option.2.hint"),
                "description" to Messages.get("action.2.description"),
                "inputs" to listOf(
                    mapOf(
                        "key" to "lyricsDir",
                        "label" to Messages.get("prompt.select.lyrics_dir"),
                        "required" to true,
                        "placeholder" to Messages.get("action.placeholder.lyrics_dir")
                    ),
                    mapOf(
                        "key" to "outDir",
                        "label" to Messages.get("prompt.select.out_dir"),
                        "required" to true,
                        "placeholder" to Messages.get("action.placeholder.unsynced_dir")
                    )
                ),
                "options" to emptyList<Any>()
            )

            3 -> mapOf(
                "id" to 3,
                "title" to Messages.get("menu.option.3.title"),
                "hint" to Messages.get("menu.option.3.hint"),
                "description" to Messages.get("action.3.description"),
                "inputs" to listOf(
                    mapOf(
                        "key" to "lyricsDir",
                        "label" to Messages.get("prompt.select.lyrics_dir"),
                        "required" to true,
                        "placeholder" to Messages.get("action.placeholder.lyrics_dir")
                    )
                ),
                "options" to listOf(
                    mapOf("key" to "removeSpeakerAndBgTags", "label" to Messages.get("action.3.opt.speaker_bg"), "type" to "checkbox", "default" to true),
                    mapOf("key" to "removeInlineWordTimestamps", "label" to Messages.get("action.3.opt.inline_word_ts"), "type" to "checkbox", "default" to true),
                    mapOf("key" to "removeCreditsAndWatermarks", "label" to Messages.get("action.3.opt.credits_watermarks"), "type" to "checkbox", "default" to true),
                    mapOf("key" to "sortAndDeduplicateMultiTimestamps", "label" to Messages.get("action.3.opt.sort_dedup_multi_ts"), "type" to "checkbox", "default" to true),
                    mapOf("key" to "normalizeTimestampsToCentiseconds", "label" to Messages.get("action.3.opt.normalize_centiseconds"), "type" to "checkbox", "default" to true),
                    mapOf("key" to "removeTrailingEmptyTimestampLines", "label" to Messages.get("action.3.opt.remove_trailing_empty_ts"), "type" to "checkbox", "default" to true),
                    mapOf("key" to "removeEmbeddedSyncedLyricsFromAudio", "label" to Messages.get("action.3.opt.remove_embedded_synced_lyrics"), "type" to "checkbox", "default" to true),
                    mapOf(
                        "key" to "removeUnsyncedLinesInSyncedFile",
                        "label" to Messages.get("action.3.opt.remove_unsynced_in_synced"),
                        "type" to "checkbox",
                        "default" to false
                    )
                )
            )

            4 -> mapOf(
                "id" to 4,
                "title" to Messages.get("menu.option.4.title"),
                "hint" to Messages.get("menu.option.4.hint"),
                "description" to Messages.get("action.4.description"),
                "inputs" to listOf(
                    mapOf(
                        "key" to "musicDir",
                        "label" to Messages.get("prompt.select.main_dir"),
                        "required" to true,
                        "placeholder" to Messages.get("action.placeholder.music_dir")
                    ),
                    mapOf(
                        "key" to "outDir",
                        "label" to Messages.get("prompt.select.move_alone_lyrics"),
                        "required" to true,
                        "placeholder" to Messages.get("action.placeholder.alone_lyrics_dir")
                    )
                ),
                "options" to listOf(
                    mapOf("key" to "includeTxtFiles", "label" to Messages.get("action.4.opt.include_txt"), "type" to "checkbox", "default" to false)
                )
            )

            5 -> mapOf(
                "id" to 5,
                "title" to Messages.get("menu.option.5.title"),
                "hint" to Messages.get("menu.option.5.hint"),
                "description" to Messages.get("action.5.description"),
                "inputs" to listOf(
                    mapOf(
                        "key" to "musicDir",
                        "label" to Messages.get("prompt.select.music_base_dir"),
                        "required" to true,
                        "placeholder" to Messages.get("action.placeholder.music_dir")
                    ), mapOf(
                        "key" to "outDir",
                        "label" to Messages.get("prompt.select.move_incorrect"),
                        "required" to false,
                        "placeholder" to Messages.get("action.placeholder.incorrect_names_dir")
                    )
                ),
                "options" to listOf(
                    mapOf("key" to "moveFiles", "label" to Messages.get("action.5.opt.move_files"), "type" to "checkbox", "default" to false)
                )
            )

            6 -> mapOf(
                "id" to 6,
                "title" to Messages.get("menu.option.6.title"),
                "hint" to Messages.get("menu.option.6.hint"),
                "description" to Messages.get("action.6.description"),
                "inputs" to listOf(
                    mapOf(
                        "key" to "musicDir",
                        "label" to Messages.get("prompt.select.spectrum_dir"),
                        "required" to true,
                        "placeholder" to Messages.get("action.placeholder.music_dir")
                    ),
                    mapOf(
                        "key" to "outDir",
                        "label" to Messages.get("prompt.select.move_fake"),
                        "required" to false,
                        "placeholder" to Messages.get("action.placeholder.fake_flac_dir")
                    )
                ),
                "options" to listOf(
                    mapOf(
                        "key" to "isFullAnalysis", "label" to Messages.get("action.6.opt.analysis_mode"), "type" to "select", "selectOptions" to listOf(
                            mapOf("value" to false, "label" to Messages.get("dialog.confirm.analysis_type_option_quick")),
                            mapOf("value" to true, "label" to Messages.get("dialog.confirm.analysis_type_option_full"))
                        ), "default" to false
                    ), mapOf("key" to "moveFiles", "label" to Messages.get("action.6.opt.move_files"), "type" to "checkbox", "default" to false)
                )
            )

            7 -> mapOf(
                "id" to 7,
                "title" to Messages.get("menu.option.7.title"),
                "hint" to Messages.get("menu.option.7.hint"),
                "description" to Messages.get("action.7.description"),
                "inputs" to listOf(
                    mapOf(
                        "key" to "musicDir",
                        "label" to Messages.get("prompt.select.music_lyrics_dir"),
                        "required" to true,
                        "placeholder" to Messages.get("action.placeholder.music_dir")
                    ), mapOf(
                        "key" to "outDir",
                        "label" to Messages.get("prompt.select.move_problematic_lyrics"),
                        "required" to false,
                        "placeholder" to Messages.get("action.placeholder.problematic_lyrics_dir")
                    )
                ),
                "options" to listOf(
                    mapOf("key" to "includeMissingLyrics", "label" to Messages.get("action.7.opt.include_missing_lyrics"), "type" to "checkbox", "default" to true),
                    mapOf("key" to "moveFiles", "label" to Messages.get("action.7.opt.move_files"), "type" to "checkbox", "default" to false)
                )
            )

            else -> mapOf("error" to Messages.get("error.invalid_action", actionId))
        }

        sendJsonResponse(exchange, 200, config)
    }

    private fun handleActionExecute(exchange: HttpExchange) {
        val bodyString = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
        val request = gson.fromJson(bodyString, ActionExecutionRequest::class.java)
        val initialStatus = actionRunner.executeAction(request)
        sendJsonResponse(exchange, 200, initialStatus)
    }

    private fun handleActionStatus(exchange: HttpExchange) {
        val status = actionRunner.getProgress()
        sendJsonResponse(exchange, 200, status)
    }

    private fun handleActionBrowseDir(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val title = queryParams["title"] ?: Messages.get("prompt.select.folder_default")
        val path = actionRunner.browseDirectory(title)
        sendJsonResponse(exchange, 200, mapOf("path" to path, "success" to (path != null)))
    }

    private fun handleListReports(exchange: HttpExchange) {
        val reportFiles = services.AppOutputDirectories.findAllSyncIssueReports().map { it.canonicalPath }

        sendJsonResponse(exchange, 200, reportFiles)
    }

    private fun handleGetItems(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val reportPath = queryParams["report"]

        val reportFile = if (!reportPath.isNullOrBlank()) {
            services.AppOutputDirectories.resolveSyncReportFile(reportPath)
        } else {
            services.AppOutputDirectories.findAllSyncIssueReports().firstOrNull() ?: File("LyricsSyncIssues_331299062.txt")
        }

        val items = parseReportUseCase.execute(reportFile).map { item ->
            item.copy(issues = ReportIssueLocalizer.localizeIssues(item.issues))
        }
        cachedItems.clear()
        items.forEach { cachedItems[it.id] = it }

        val simplifiedItems = items.map {
            mapOf(
                "id" to it.id, "fileName" to it.fileName, "issues" to it.issues, "status" to it.status
            )
        }

        sendJsonResponse(exchange, 200, simplifiedItems)
    }

    private fun handleGetTrack(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val id = queryParams["id"]?.toIntOrNull()

        if (id == null || !cachedItems.containsKey(id)) {
            sendJsonResponse(exchange, 404, mapOf("error" to Messages.get("error.track_not_found", id.toString())))
            return
        }

        val item = cachedItems[id] ?: return
        val localizedItem = item.copy(issues = ReportIssueLocalizer.localizeIssues(item.issues))
        val details = extractDetailsUseCase.execute(localizedItem)
        sendJsonResponse(exchange, 200, details)
    }

    private fun handleStreamAudio(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val audioPath = queryParams["path"]
        if (audioPath.isNullOrBlank()) {
            sendJsonResponse(exchange, 400, mapOf("error" to Messages.get("error.missing_audio_path")))
            return
        }

        val audioFile = File(audioPath)
        audioStreamer.streamAudio(exchange, audioFile)
    }

    private fun handleGetArtwork(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val audioPath = queryParams["path"]
        if (audioPath.isNullOrBlank()) {
            sendJsonResponse(exchange, 400, mapOf("error" to Messages.get("error.missing_audio_path")))
            return
        }

        val audioFile = File(audioPath)
        val artworkBytes = audioMetadataExtractor.extractArtworkBytes(audioFile)

        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            exchange.responseHeaders.set("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, artworkBytes.size.toLong())
            exchange.responseBody.use { it.write(artworkBytes) }
        } else {
            exchange.sendResponseHeaders(404, 0)
            exchange.responseBody.close()
        }
    }

    private fun <T> withComparatorRun(block: () -> T): T {
        return AuditRunContext.withRun(
            actionId = 8,
            actionKey = "menu.option.8.title",
            actionFallback = Messages.get("menu.option.8.title")
        ) {
            block()
        }
    }

    private fun handleSaveLyric(exchange: HttpExchange) {
        val bodyString = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
        val request = gson.fromJson(bodyString, SaveLyricRequest::class.java)

        val result = withComparatorRun {
            saveLyricUseCase.execute(request)
        }

        cachedItems.values.find { it.lyricFile.absolutePath == request.lyricPath }?.let {
            it.status = if (request.choice.equals("LRCLIB", true)) "SAVED_LRCLIB" else "KEPT_LOCAL"
        }

        sendJsonResponse(exchange, 200, result)
    }

    private fun handleGenerateInstrumental(exchange: HttpExchange) {
        val bodyMap = parseJsonBodyMap(exchange)

        val itemId = (bodyMap["id"] as? Number)?.toInt()
        val cachedItem = itemId?.let { cachedItems[it] }
        val lyricPath = (bodyMap["lyricPath"] as? String)?.takeIf { it.isNotBlank() } ?: cachedItem?.lyricFile?.absolutePath ?: ""

        var durationSeconds = (bodyMap["durationSeconds"] as? Number)?.toInt() ?: 0
        if (durationSeconds <= 0 && cachedItem?.audioFile != null && cachedItem.audioFile.exists()) {
            durationSeconds = audioMetadataExtractor.extractMetadata(cachedItem.audioFile).durationSeconds
        }

        val lang = bodyMap["lang"] as? String
        val customTag = bodyMap["customTag"] as? String
        val autoSave = (bodyMap["autoSave"] as? Boolean) ?: true

        val lrcContent = ExtractTrackDetailsUseCase.generateInstrumentalLyrics(
            durationSeconds, lang, customTag
        )
        val parsedLines = parseLinesUseCase.execute(lrcContent)

        if (autoSave && lyricPath.isNotBlank()) {
            val saveRes = withComparatorRun {
                saveLyricUseCase.execute(
                    SaveLyricRequest(
                        lyricPath, "LOCAL", lrcContent
                    )
                )
            }
            if (saveRes.success) {
                cachedItems.values.find { it.lyricFile.absolutePath == lyricPath }?.let {
                    it.status = "KEPT_LOCAL"
                }
            }
            sendJsonResponse(
                exchange, 200, mapOf(
                    "success" to saveRes.success, "message" to saveRes.message, "lrcContent" to lrcContent, "lines" to parsedLines, "status" to "KEPT_LOCAL"
                )
            )
        } else {
            sendJsonResponse(
                exchange, 200, mapOf(
                    "success" to true, "lrcContent" to lrcContent, "lines" to parsedLines
                )
            )
        }
    }

    private fun saveBestCandidateForItem(item: ReportIssueItem): Map<String, Any?> {
        val details = extractDetailsUseCase.execute(item)
        val bestCandidate = details.candidates.firstOrNull()
        val bestLyrics = bestCandidate?.let { it.syncedLyrics ?: it.plainLyrics } ?: details.remoteRawLyrics

        if (bestLyrics.isNullOrBlank()) {
            return mapOf(
                "id" to item.id, "fileName" to item.fileName, "success" to false, "status" to item.status, "message" to Messages.get("save.error.no_compatible_lyric")
            )
        }

        val saveRes = saveLyricUseCase.execute(
            SaveLyricRequest(
                item.lyricFile.absolutePath, bestLyrics, bestLyrics
            )
        )
        if (saveRes.success) {
            item.status = "SAVED_LRCLIB"
        }

        return mapOf(
            "id" to item.id,
            "fileName" to item.fileName,
            "success" to saveRes.success,
            "status" to item.status,
            "source" to (bestCandidate?.source ?: "LRCLIB"),
            "duration" to bestCandidate?.duration,
            "message" to saveRes.message
        )
    }

    private fun handleSaveBestForTrack(exchange: HttpExchange) {
        val bodyMap = parseJsonBodyMap(exchange)
        val queryParams = parseQueryParams(exchange)
        val id = (bodyMap["id"] as? Number)?.toInt() ?: queryParams["id"]?.toIntOrNull()

        if (id == null || !cachedItems.containsKey(id)) {
            sendJsonResponse(exchange, 404, mapOf("error" to Messages.get("error.track_not_found", id.toString())))
            return
        }

        val item = cachedItems[id] ?: return
        val res = withComparatorRun { saveBestCandidateForItem(item) }
        sendJsonResponse(exchange, 200, res)
    }

    private fun handleBatchSaveBest(exchange: HttpExchange) {
        val bodyMap = parseJsonBodyMap(exchange)

        val requestedIds = (bodyMap["ids"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
        val onlyPending = (bodyMap["onlyPending"] as? Boolean) ?: true

        val targetItems = when {
            !requestedIds.isNullOrEmpty() -> requestedIds.mapNotNull { cachedItems[it] }
            onlyPending -> cachedItems.values.filter { it.status == "PENDING" }
            else -> cachedItems.values.toList()
        }

        val results = withComparatorRun {
            targetItems.map { item ->
                saveBestCandidateForItem(item)
            }
        }
        val savedCount = results.count { it["success"] == true }

        sendJsonResponse(
            exchange, 200, mapOf(
                "total" to targetItems.size, "savedCount" to savedCount, "failedCount" to (targetItems.size - savedCount), "results" to results
            )
        )
    }

    private fun handleSearchRemote(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val query = queryParams["q"].orEmpty()
        val results = fetchLrcLibUseCase.search(query)
        sendJsonResponse(exchange, 200, results)
    }

    private fun handleOpenLocation(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val filePath = queryParams["path"].orEmpty()
        val opened = openFileLocationUseCase.execute(filePath)
        sendJsonResponse(exchange, 200, mapOf("success" to opened, "path" to filePath))
    }

    private fun handleParseLrc(exchange: HttpExchange) {
        val bodyString = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
        val syncedLines = parseLinesUseCase.execute(bodyString)
        val lines = syncedLines.ifEmpty {
            parseLinesUseCase.executePlain(bodyString)
        }
        sendJsonResponse(exchange, 200, lines)
    }

    private fun handleCleanLrc(exchange: HttpExchange) {
        val bodyMap = parseJsonBodyMap(exchange)
        val rawContent = (bodyMap["content"] as? String).orEmpty()
        val options = LyricCleanOptions(
            removeSpeakerAndBgTags = (bodyMap["removeSpeakerAndBgTags"] as? Boolean) ?: true,
            removeInlineWordTimestamps = (bodyMap["removeInlineWordTimestamps"] as? Boolean) ?: true,
            removeCreditsAndWatermarks = (bodyMap["removeCreditsAndWatermarks"] as? Boolean) ?: true,
            sortAndDeduplicateMultiTimestamps = (bodyMap["sortAndDeduplicateMultiTimestamps"] as? Boolean) ?: true,
            removeUnsyncedLinesInSyncedFile = (bodyMap["removeUnsyncedLinesInSyncedFile"] as? Boolean) ?: true,
            normalizeTimestampsToCentiseconds = (bodyMap["normalizeTimestampsToCentiseconds"] as? Boolean) ?: true,
            removeTrailingEmptyTimestampLines = (bodyMap["removeTrailingEmptyTimestampLines"] as? Boolean) ?: true
        )
        val cleanResult = lyricContentCleaner.clean(rawContent, options)
        val syncedLines = parseLinesUseCase.execute(cleanResult.cleanedContent)
        val lines = syncedLines.ifEmpty {
            parseLinesUseCase.executePlain(cleanResult.cleanedContent)
        }
        sendJsonResponse(
            exchange,
            200,
            mapOf(
                "modified" to cleanResult.modified,
                "appliedFixes" to cleanResult.appliedFixes,
                "cleanedContent" to cleanResult.cleanedContent,
                "lines" to lines
            )
        )
    }

    private fun handleCleanEmbeddedLyrics(exchange: HttpExchange) {
        val bodyMap = parseJsonBodyMap(exchange)
        val itemId = (bodyMap["id"] as? Number)?.toInt()
        val cachedItem = itemId?.let { cachedItems[it] }
        val audioPathParam = (bodyMap["audioPath"] as? String)?.takeIf { it.isNotBlank() }
        val audioFile = audioPathParam?.let { File(it) } ?: cachedItem?.audioFile

        if (audioFile == null) {
            sendJsonResponse(exchange, 400, mapOf("success" to false, "error" to Messages.get("error.missing_audio_path")))
            return
        }
        if (!audioFile.exists() || !audioFile.isFile) {
            sendJsonResponse(exchange, 404, mapOf("success" to false, "error" to Messages.get("error.audio_not_found", audioFile.absolutePath)))
            return
        }

        withComparatorRun {
            try {
                when (val outcome = EmbeddedLyricsHelper.removeEmbeddedSyncedLyricsWithOutcome(audioFile)) {
                    is EmbeddedCleanupOutcome.Removed -> {
                        val removedInfo = outcome.info
                        val tagsDesc = removedInfo.tagKeys.joinToString(", ")
                        auditLogService.logContentModification(
                            operation = "Comparator - Remove Embedded Synced Lyrics",
                            targetFile = audioFile,
                            beforeContent = removedInfo.content,
                            afterContent = "(removed embedded tags: $tagsDesc)",
                            metadata = mapOf("removedTags" to tagsDesc)
                        )
                        var remainingIssues: List<String>? = null
                        if (cachedItem != null) {
                            remainingIssues = cachedItem.issues.filterNot { iss ->
                                val upper = iss.uppercase()
                                upper.contains("[LETRA_EMBUTIDA]") ||
                                    upper.contains("[EMBEDDED_LYRICS]") ||
                                    upper.contains("LETRA EMBUTIDA COM TIMESTAMPS") ||
                                    upper.contains("EMBEDDED LYRICS WITH TIMESTAMPS")
                            }
                            cachedItems[cachedItem.id] = cachedItem.copy(issues = remainingIssues)
                        }
                        sendJsonResponse(
                            exchange,
                            200,
                            mapOf(
                                "success" to true,
                                "modified" to true,
                                "removedTags" to removedInfo.tagKeys,
                                "remainingIssues" to remainingIssues
                            )
                        )
                    }

                    is EmbeddedCleanupOutcome.SkippedUnsupported -> {
                        val reasonArgsArray = outcome.reasonArgs.toTypedArray<Any>()
                        auditLogService.logSkipped(
                            operation = "Comparator - Remove Embedded Synced Lyrics",
                            targetFile = audioFile,
                            reasonKey = outcome.reasonKey,
                            reasonArgs = outcome.reasonArgs,
                            metadata = mapOf("extension" to outcome.extension)
                        )
                        sendJsonResponse(
                            exchange,
                            200,
                            mapOf(
                                "success" to false,
                                "modified" to false,
                                "unsupported" to true,
                                "removedTags" to emptyList<String>(),
                                "error" to Messages.get(outcome.reasonKey, *reasonArgsArray)
                            )
                        )
                    }

                    EmbeddedCleanupOutcome.Clean -> {
                        sendJsonResponse(
                            exchange,
                            200,
                            mapOf(
                                "success" to true,
                                "modified" to false,
                                "removedTags" to emptyList<String>()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                auditLogService.logError("Comparator - Remove Embedded Synced Lyrics", audioFile, e)
                sendJsonResponse(
                    exchange,
                    500,
                    mapOf(
                        "success" to false,
                        "modified" to false,
                        "error" to (e.message ?: e.toString())
                    )
                )
            }
        }
    }

    private fun handleHeartbeat(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val bodyMap = parseJsonBodyMap(exchange)
        val clientId = queryParams["clientId"] ?: (bodyMap["clientId"] as? String) ?: "default"
        recordClientActivity(clientId)
        sendJsonResponse(exchange, 200, mapOf("alive" to true))
    }

    private fun handleShutdown(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val bodyMap = parseJsonBodyMap(exchange)
        val clientId = queryParams["clientId"] ?: (bodyMap["clientId"] as? String)
        val shutdownTimestamp = System.currentTimeMillis()

        if (!clientId.isNullOrBlank()) {
            activeClients.remove(clientId)
        } else {
            activeClients.clear()
        }

        // Remove stale clients not seen within the last 15 seconds
        activeClients.entries.removeIf { (_, lastSeen) ->
            shutdownTimestamp - lastSeen > 15_000L
        }

        sendJsonResponse(exchange, 200, mapOf("shuttingDown" to true))

        if (activeClients.isEmpty()) {
            synchronized(shutdownLock) {
                pendingShutdownFuture?.cancel(false)
                pendingShutdownFuture = shutdownScheduler.schedule({
                    if (activeClients.isEmpty() && lastActivityMs.get() <= shutdownTimestamp) {
                        onShutdownRequested?.invoke()
                    }
                }, shutdownGracePeriodMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, data: Any) {
        val jsonString = gson.toJson(data)
        val path = exchange.requestURI.path
        // Log all API requests except repetitive status/heartbeat/locale/log-reader endpoints
        val shouldLog = (
            path != "/api/action/status" &&
                path != "/api/heartbeat" &&
                path != "/api/locale" &&
                !path.startsWith("/api/logs/")
            ) || statusCode != 200
        if (shouldLog) {
            LoggingInitializer.logHttpExchange(
                Messages.get("log.web.router_category"), exchange.requestMethod, exchange.requestURI.toString(), responseStatus = statusCode, responseBody = jsonString
            )
        }

        val jsonBytes = jsonString.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(statusCode, jsonBytes.size.toLong())
        exchange.responseBody.use { it.write(jsonBytes) }
    }

    private fun parseJsonBodyMap(exchange: HttpExchange): Map<String, Any?> {
        val bodyString = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
        if (bodyString.isBlank()) return emptyMap()
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            gson.fromJson<Map<String, Any?>>(bodyString, mapType) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseQueryParams(exchange: HttpExchange): Map<String, String> = parseQueryParams(exchange.requestURI.rawQuery)

    companion object {
        /**
         * Parses the raw query string into a map of decoded keys and values.
         * Note: Uses [HttpExchange.getRequestURI]'s rawQuery to prevent premature decoding
         * of '&' or '+' characters contained within parameter values (e.g. "Bruno & Marrone").
         */
        fun parseQueryParams(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            return rawQuery.split("&").filter { it.isNotBlank() }.associate { param ->
                val parts = param.split("=", limit = 2)
                val key = try {
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                    parts[0]
                }
                val rawValue = if (parts.size > 1) parts[1] else ""
                val value = try {
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                    rawValue
                }
                key to value
            }
        }
    }
}
