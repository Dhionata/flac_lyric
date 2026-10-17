package comparison.infrastructure.web

import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.io.RandomAccessFile
import services.Messages

/**
 * Handles HTTP partial-content (Range requests) streaming for audio files.
 * Allows browsers to seek instantly to any position in large audio files like FLAC.
 */
class HttpAudioStreamer {

    fun streamAudio(exchange: HttpExchange, file: File) {
        if (!file.exists() || !file.isFile) {
            val response = Messages.get("error.audio_not_found", file.name).toByteArray()
            exchange.sendResponseHeaders(404, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            return
        }

        val fileLength = file.length()
        val rangeHeader = exchange.requestHeaders.getFirst("Range")

        val mimeType = when (file.extension.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            else -> "audio/flac"
        }

        exchange.responseHeaders.set("Content-Type", mimeType)
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        exchange.responseHeaders.set("Cache-Control", "no-cache")

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.substringAfter("bytes=").trim()
            val parts = range.split("-")
            val start = parts[0].toLongOrNull() ?: 0L
            val end = if (parts.size > 1 && parts[1].isNotBlank()) {
                parts[1].toLongOrNull() ?: (fileLength - 1)
            } else {
                fileLength - 1
            }.coerceAtMost(fileLength - 1)

            val contentLength = (end - start + 1).coerceAtLeast(0)

            exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$fileLength")
            exchange.sendResponseHeaders(206, contentLength)

            try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    exchange.responseBody.use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = contentLength
                        while (remaining > 0) {
                            val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                            val bytesRead = raf.read(buffer, 0, toRead)
                            if (bytesRead == -1) break
                            out.write(buffer, 0, bytesRead)
                            out.flush()
                            remaining -= bytesRead
                        }
                    }
                }
            } catch (_: Exception) {
                // Client aborted or socket closed while seeking; expected during audio playback
            }
        } else {
            try {
                exchange.sendResponseHeaders(200, fileLength)
                file.inputStream().use { input ->
                    exchange.responseBody.use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {
                // Client aborted or socket closed
            }
        }
    }
}
