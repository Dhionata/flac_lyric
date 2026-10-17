package comparison.infrastructure.web

import com.google.gson.Gson
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
import comparison.infrastructure.services.WebActionRunnerService
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
    private val audioStreamer: HttpAudioStreamer = HttpAudioStreamer(),
    private val actionRunner: WebActionRunnerService = WebActionRunnerService(),
    private val gson: Gson = Gson(),
) : HttpHandler {

    private val cachedItems = mutableMapOf<Int, ReportIssueItem>()

    override fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod

        try {
            when {
                path == "/" && method == "GET" -> handleIndex(exchange)
                path == "/api/reports" && method == "GET" -> handleListReports(exchange)
                path == "/api/items" && method == "GET" -> handleGetItems(exchange)
                path == "/api/track" && method == "GET" -> handleGetTrack(exchange)
                path == "/api/audio" && method == "GET" -> handleStreamAudio(exchange)
                path == "/api/artwork" && method == "GET" -> handleGetArtwork(exchange)
                path == "/api/save" && method == "POST" -> handleSaveLyric(exchange)
                path == "/api/search" && method == "GET" -> handleSearchRemote(exchange)
                path == "/api/open-location" && method == "GET" -> handleOpenLocation(exchange)
                path == "/api/parse-lrc" && method == "POST" -> handleParseLrc(exchange)
                path == "/api/menu-options" && method == "GET" -> handleMenuOptions(exchange)
                path == "/api/action/execute" && method == "POST" -> handleActionExecute(exchange)
                path == "/api/action/status" && method == "GET" -> handleActionStatus(exchange)
                path == "/api/action/browse-dir" && method == "GET" -> handleActionBrowseDir(exchange)
                path == "/api/action/config" && method == "GET" -> handleActionConfig(exchange)
                path == "/api/locale" && method == "GET" -> handleLocale(exchange)
                else -> sendJsonResponse(exchange, 404, mapOf("error" to Messages.get("error.endpoint_not_found", path)))
            }
        } catch (e: Exception) {
            sendJsonResponse(exchange, 500, mapOf("error" to (e.message ?: e.toString())))
        }
    }

    private fun handleLocale(exchange: HttpExchange) {
        val lang = Messages.getLocale().toLanguageTag()
        sendJsonResponse(exchange, 200, mapOf("locale" to lang, "isPortuguese" to lang.lowercase().startsWith("pt")))
    }

    private fun handleIndex(exchange: HttpExchange) {
        val htmlBytes = WebStaticAssets.INDEX_HTML.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, htmlBytes.size.toLong())
        exchange.responseBody.use { it.write(htmlBytes) }
    }

    private fun handleMenuOptions(exchange: HttpExchange) {
        val options = (0..8).map { index ->
            mapOf(
                "id" to index,
                "title" to Messages.get("menu.option.$index.title"),
                "hint" to Messages.get("menu.option.$index.hint"),
                "isComparator" to (index == 8)
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
                    mapOf("key" to "musicDir", "label" to Messages.get("prompt.select.music_dir"), "required" to true, "placeholder" to "Ex: C:\\Musicas"),
                    mapOf("key" to "lyricsDir", "label" to Messages.get("prompt.select.lyrics_dir"), "required" to true, "placeholder" to "Ex: C:\\Letras")
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
                    mapOf("key" to "musicDir", "label" to Messages.get("prompt.select.music_dir"), "required" to true, "placeholder" to "Ex: C:\\Musicas")
                ),
                "options" to emptyList<Any>()
            )

            2 -> mapOf(
                "id" to 2,
                "title" to Messages.get("menu.option.2.title"),
                "hint" to Messages.get("menu.option.2.hint"),
                "description" to Messages.get("action.2.description"),
                "inputs" to listOf(
                    mapOf("key" to "lyricsDir", "label" to Messages.get("prompt.select.lyrics_dir"), "required" to true, "placeholder" to "Ex: C:\\Letras"),
                    mapOf("key" to "outDir", "label" to Messages.get("prompt.select.out_dir"), "required" to true, "placeholder" to "Ex: C:\\NaoSincronizadas")
                ),
                "options" to emptyList<Any>()
            )

            3 -> mapOf(
                "id" to 3,
                "title" to Messages.get("menu.option.3.title"),
                "hint" to Messages.get("menu.option.3.hint"),
                "description" to Messages.get("action.3.description"),
                "inputs" to listOf(
                    mapOf("key" to "lyricsDir", "label" to Messages.get("prompt.select.lyrics_dir"), "required" to true, "placeholder" to "Ex: C:\\Letras")
                ),
                "options" to emptyList<Any>()
            )

            4 -> mapOf(
                "id" to 4,
                "title" to Messages.get("menu.option.4.title"),
                "hint" to Messages.get("menu.option.4.hint"),
                "description" to Messages.get("action.4.description"),
                "inputs" to listOf(
                    mapOf("key" to "musicDir", "label" to Messages.get("prompt.select.main_dir"), "required" to true, "placeholder" to "Ex: C:\\Musicas"),
                    mapOf("key" to "outDir", "label" to Messages.get("prompt.select.move_alone_lyrics"), "required" to true, "placeholder" to "Ex: C:\\LetrasIsoladas")
                ),
                "options" to emptyList<Any>()
            )

            5 -> mapOf(
                "id" to 5,
                "title" to Messages.get("menu.option.5.title"),
                "hint" to Messages.get("menu.option.5.hint"),
                "description" to Messages.get("action.5.description"),
                "inputs" to listOf(
                    mapOf("key" to "musicDir", "label" to Messages.get("prompt.select.music_base_dir"), "required" to true, "placeholder" to "Ex: C:\\Musicas"),
                    mapOf(
                        "key" to "outDir",
                        "label" to Messages.get("prompt.select.move_incorrect"),
                        "required" to false,
                        "placeholder" to "Ex: C:\\NomesIncorretos (opcional)"
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
                    mapOf("key" to "musicDir", "label" to Messages.get("prompt.select.spectrum_dir"), "required" to true, "placeholder" to "Ex: C:\\Musicas"),
                    mapOf("key" to "outDir", "label" to Messages.get("prompt.select.move_fake"), "required" to false, "placeholder" to "Ex: C:\\FalsosFlac (opcional)")
                ),
                "options" to listOf(
                    mapOf(
                        "key" to "isFullAnalysis", "label" to Messages.get("action.6.opt.analysis_mode"), "type" to "select", "selectOptions" to listOf(
                            mapOf("value" to false, "label" to Messages.get("dialog.confirm.analysis_type_option_quick")),
                            mapOf("value" to true, "label" to Messages.get("dialog.confirm.analysis_type_option_full"))
                        ), "default" to false
                    ),
                    mapOf("key" to "moveFiles", "label" to Messages.get("action.6.opt.move_files"), "type" to "checkbox", "default" to false)
                )
            )

            7 -> mapOf(
                "id" to 7,
                "title" to Messages.get("menu.option.7.title"),
                "hint" to Messages.get("menu.option.7.hint"),
                "description" to Messages.get("action.7.description"),
                "inputs" to listOf(
                    mapOf("key" to "musicDir", "label" to Messages.get("prompt.select.music_lyrics_dir"), "required" to true, "placeholder" to "Ex: C:\\Musicas"),
                    mapOf(
                        "key" to "outDir",
                        "label" to Messages.get("prompt.select.move_problematic_lyrics"),
                        "required" to false,
                        "placeholder" to "Ex: C:\\LetrasComProblemas (opcional)"
                    )
                ),
                "options" to listOf(
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
        val rootDir = File(".")
        val reportFiles = rootDir.listFiles { file ->
            file.isFile && file.name.startsWith("LyricsSyncIssues_") && file.name.endsWith(".txt")
        }?.map { it.canonicalPath } ?: emptyList()

        sendJsonResponse(exchange, 200, reportFiles)
    }

    private fun handleGetItems(exchange: HttpExchange) {
        val queryParams = parseQueryParams(exchange)
        val reportPath = queryParams["report"]

        val reportFile = if (!reportPath.isNullOrBlank()) {
            File(reportPath)
        } else {
            File(".").listFiles { f ->
                f.isFile && f.name.startsWith("LyricsSyncIssues_") && f.name.endsWith(".txt")
            }?.firstOrNull() ?: File("LyricsSyncIssues_331299062.txt")
        }

        val items = parseReportUseCase.execute(reportFile)
        cachedItems.clear()
        items.forEach { cachedItems[it.id] = it }

        val simplifiedItems = items.map {
            mapOf(
                "id" to it.id,
                "fileName" to it.fileName,
                "issues" to it.issues,
                "status" to it.status
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

        val item = cachedItems[id]!!
        val details = extractDetailsUseCase.execute(item)
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

    private fun handleSaveLyric(exchange: HttpExchange) {
        val bodyString = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
        val request = gson.fromJson(bodyString, SaveLyricRequest::class.java)

        val result = saveLyricUseCase.execute(request)

        cachedItems.values.find { it.lyricFile.absolutePath == request.lyricPath }?.let {
            it.status = if (request.choice.equals("LRCLIB", ignoreCase = true)) "SAVED_LRCLIB" else "KEPT_LOCAL"
        }

        sendJsonResponse(exchange, 200, result)
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
        val lines = if (syncedLines.isNotEmpty()) {
            syncedLines
        } else {
            parseLinesUseCase.executePlain(bodyString)
        }
        sendJsonResponse(exchange, 200, lines)
    }

    private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, data: Any) {
        val jsonBytes = gson.toJson(data).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(statusCode, jsonBytes.size.toLong())
        exchange.responseBody.use { it.write(jsonBytes) }
    }

    private fun parseQueryParams(exchange: HttpExchange): Map<String, String> =
        parseQueryParams(exchange.requestURI.rawQuery)

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
