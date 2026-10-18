package comparison.infrastructure.clients

import com.google.gson.JsonParser
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import services.LoggingInitializer

/**
 * OkHttp Interceptor that handles HTTP 429 (Too Many Requests) rate-limiting across all lyrics APIs
 * and logs every HTTP request and response (with pretty-printed JSON) to the per-execution `.log` file.
 *
 * Strategy:
 * 1. Per-host concurrency gate (Semaphore) to prevent massive burst spikes when running multiple query variants.
 * 2. Shared per-host cooldown window (`hostCooldownUntilMs`): if one thread receives HTTP 429 from a host,
 *    other concurrent threads targeting the same host wait until the API cooldown expires before sending.
 * 3. Exact retry timing: parses `Retry-After`, `X-RateLimit-Reset-After`, `RateLimit-Reset`, `X-RateLimit-Reset`,
 *    `Retry-After-Ms`, or JSON body fields (`retry_after`, `retryAfter`, `retry_after_ms`) to send the retry
 *    as soon as the API permits.
 * 4. 1-second fallback: when the 429 response does not specify a wait time, waits 1000ms (1s) and retries.
 */
class HttpRateLimitInterceptor(
    private val maxRetries: Int = 3,
    private val defaultFallbackDelayMs: Long = 1_000L,
    private val maxSingleWaitMs: Long = 30_000L,
    private val maxConcurrentPerHost: Int = 10,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
) : Interceptor {

    companion object {
        private val sharedHostCooldownUntilMs = ConcurrentHashMap<String, Long>()
        private val sharedHostSemaphores = ConcurrentHashMap<String, Semaphore>()

        val sharedInstance = HttpRateLimitInterceptor()

        fun createDefaultHttpClient(
            connectTimeoutSeconds: Long = 5L,
            readTimeoutSeconds: Long = 7L,
            interceptor: HttpRateLimitInterceptor = sharedInstance,
        ): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .addInterceptor(interceptor)
                .build()
        }

        /**
         * Extracts the wait duration in milliseconds from an HTTP 429 response,
         * or returns [fallbackDelayMs] (default 1000ms = 1s) if the response does not specify one.
         */
        fun extractRetryDelayMs(
            response: Response,
            nowMs: Long = System.currentTimeMillis(),
            fallbackDelayMs: Long = 1_000L,
            maxWaitMs: Long = 30_000L,
        ): Long {
            // 1. Check millisecond headers first
            val retryAfterMsHeader = response.header("Retry-After-Ms")
                ?: response.header("X-Retry-After-Ms")
            if (!retryAfterMsHeader.isNullOrBlank()) {
                val parsedMs = retryAfterMsHeader.trim().toDoubleOrNull()
                if (parsedMs != null && parsedMs > 0) {
                    return ceil(parsedMs).toLong().coerceIn(1L, maxWaitMs)
                }
            }

            // 2. Check standard Retry-After / RateLimit-Reset-After headers (seconds or HTTP-date)
            val retryHeaderCandidates = listOfNotNull(
                response.header("Retry-After"),
                response.header("X-RateLimit-Reset-After"),
                response.header("RateLimit-Reset"),
                response.header("X-Retry-After")
            )

            for (rawHeader in retryHeaderCandidates) {
                val trimmed = rawHeader.trim()
                val numericSeconds = trimmed.toDoubleOrNull()
                if (numericSeconds != null && numericSeconds > 0) {
                    val delayMs = if (numericSeconds > 1_000_000_000.0) {
                        val resetEpochMs = if (numericSeconds > 1_000_000_000_000.0) {
                            numericSeconds.toLong()
                        } else {
                            (numericSeconds * 1000.0).toLong()
                        }
                        (resetEpochMs - nowMs).coerceAtLeast(100L)
                    } else {
                        ceil(numericSeconds * 1000.0).toLong().coerceAtLeast(1L)
                    }
                    return delayMs.coerceIn(1L, maxWaitMs)
                }

                // Try RFC-1123 HTTP-date format
                try {
                    val date = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                    val diffMs = Duration.between(Instant.ofEpochMilli(nowMs), date.toInstant()).toMillis()
                    if (diffMs > 0) {
                        return diffMs.coerceIn(1L, maxWaitMs)
                    }
                } catch (_: Exception) {
                }
            }

            // 3. Check X-RateLimit-Reset (typically epoch seconds or epoch ms)
            val resetHeader = response.header("X-RateLimit-Reset")?.trim()
            if (!resetHeader.isNullOrBlank()) {
                val resetVal = resetHeader.toDoubleOrNull()
                if (resetVal != null && resetVal > 0) {
                    val delayMs = if (resetVal > 1_000_000_000_000.0) {
                        (resetVal.toLong() - nowMs)
                    } else if (resetVal > 1_000_000_000.0) {
                        ((resetVal * 1000.0).toLong() - nowMs)
                    } else {
                        ceil(resetVal * 1000.0).toLong()
                    }
                    if (delayMs > 0) {
                        return delayMs.coerceIn(1L, maxWaitMs)
                    }
                }
            }

            // 4. Inspect JSON body for retry_after / retryAfter / retry_after_ms fields
            try {
                val bodyPreview = response.peekBody(4096).string().trim()
                if (bodyPreview.startsWith("{")) {
                    val json = JsonParser.parseString(bodyPreview).asJsonObject
                    val msKeys = listOf("retry_after_ms", "retryAfterMs", "wait_ms", "waitMs")
                    for (key in msKeys) {
                        val elem = json.get(key)
                        if (elem != null && !elem.isJsonNull && elem.isJsonPrimitive) {
                            val v = elem.asString.toDoubleOrNull()
                            if (v != null && v > 0) {
                                return ceil(v).toLong().coerceIn(1L, maxWaitMs)
                            }
                        }
                    }

                    val secKeys = listOf("retry_after", "retryAfter", "reset_in", "resetIn", "wait")
                    for (key in secKeys) {
                        val elem = json.get(key)
                        if (elem != null && !elem.isJsonNull && elem.isJsonPrimitive) {
                            val v = elem.asString.toDoubleOrNull()
                            if (elem.isJsonPrimitive && v != null && v > 0) {
                                return ceil(v * 1000.0).toLong().coerceIn(1L, maxWaitMs)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }

            // 5. Fallback to 1 second (1000ms) when no wait time is provided by the API
            return fallbackDelayMs
        }
    }

    private val localHostCooldowns = ConcurrentHashMap<String, Long>()
    private val localHostSemaphores = ConcurrentHashMap<String, Semaphore>()

    private fun cooldownMap(): ConcurrentHashMap<String, Long> =
        if (this === sharedInstance) sharedHostCooldownUntilMs else localHostCooldowns

    private fun semaphoreForHost(host: String): Semaphore {
        val map = if (this === sharedInstance) sharedHostSemaphores else localHostSemaphores
        return map.computeIfAbsent(host.lowercase()) { Semaphore(maxConcurrentPerHost) }
    }

    private fun waitForHostCooldownIfNeeded(host: String) {
        val key = host.lowercase()
        val cooldownUntil = cooldownMap()[key] ?: return
        val now = clock()
        val waitMs = cooldownUntil - now
        if (waitMs > 0) {
            sleeper(waitMs.coerceAtMost(maxSingleWaitMs))
        }
    }

    private fun recordHostCooldown(host: String, delayMs: Long): Long {
        val key = host.lowercase()
        val now = clock()
        val targetUntil = now + delayMs
        var effectiveWait = delayMs
        cooldownMap().compute(key) { _, existingUntil ->
            if (existingUntil == null || targetUntil > existingUntil) {
                effectiveWait = delayMs
                targetUntil
            } else {
                effectiveWait = (existingUntil - now).coerceAtLeast(delayMs)
                existingUntil
            }
        }
        return effectiveWait.coerceIn(1L, maxSingleWaitMs)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val semaphore = semaphoreForHost(host)

        val reqHeadersMap = request.headers.names().associateWith { name ->
            request.headers.values(name).joinToString(", ")
        }
        val reqBodyPreview = try {
            val body = request.body
            if (body != null && body.contentLength() in 1..65536) {
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        var attempt = 0
        while (true) {
            waitForHostCooldownIfNeeded(host)

            val startMs = clock()
            semaphore.acquire()
            val response = try {
                chain.proceed(request)
            } catch (e: Exception) {
                semaphore.release()
                val elapsed = (clock() - startMs).coerceAtLeast(0L)
                LoggingInitializer.logHttpExchange(
                    category = "API EXTERNA ($host) [Tentativa ${attempt + 1}]",
                    method = request.method,
                    url = request.url.toString(),
                    requestHeaders = reqHeadersMap,
                    requestBody = reqBodyPreview,
                    durationMs = elapsed,
                    error = "${e.javaClass.simpleName}: ${e.message}"
                )
                throw e
            }
            semaphore.release()
            val elapsedMs = (clock() - startMs).coerceAtLeast(0L)

            val respHeadersMap = response.headers.names().associateWith { name ->
                response.headers.values(name).joinToString(", ")
            }
            val respBodyPreview = try {
                response.peekBody(1024L * 1024L).string()
            } catch (_: Exception) {
                null
            }

            LoggingInitializer.logHttpExchange(
                category = "API EXTERNA ($host) [Tentativa ${attempt + 1}]",
                method = request.method,
                url = request.url.toString(),
                requestHeaders = reqHeadersMap,
                requestBody = reqBodyPreview,
                responseStatus = response.code,
                durationMs = elapsedMs,
                responseHeaders = respHeadersMap,
                responseBody = respBodyPreview
            )

            if (response.code != 429 || attempt >= maxRetries) {
                return response
            }

            val retryDelayMs = extractRetryDelayMs(
                response = response,
                nowMs = clock(),
                fallbackDelayMs = defaultFallbackDelayMs,
                maxWaitMs = maxSingleWaitMs
            )
            response.close()

            val effectiveWaitMs = recordHostCooldown(host, retryDelayMs)
            sleeper(effectiveWaitMs)
            attempt++
        }
    }
}
