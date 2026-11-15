package services

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-local context tracking the active execution run (`runId`) and menu action (`actionId`, `actionKey`)
 * so nested services (`FileServiceImpl`, `MusicLyricsService`, `SaveLyricChoiceUseCase`, etc.) automatically
 * tag their audit events with the parent operation without changing method signatures.
 */
object AuditRunContext {
    data class Scope(
        val runId: String,
        val actionId: Int? = null,
        val actionKey: String? = null,
        val actionFallback: String? = null,
    )

    private val currentScope = ThreadLocal<Scope?>()
    private val runCounter = AtomicLong(0)
    private val runTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun current(): Scope? = currentScope.get()

    fun newRunId(prefix: String = "run"): String {
        val ts = LocalDateTime.now().format(runTimestampFormatter)
        val seq = runCounter.incrementAndGet()
        return "$prefix-$ts-$seq"
    }

    inline fun <T> withRun(
        actionId: Int? = null,
        actionKey: String? = null,
        actionFallback: String? = null,
        runId: String = newRunId(actionId?.let { "op$it" } ?: "run"),
        block: (Scope) -> T,
    ): T {
        val previous = current()
        val next = Scope(
            runId = runId,
            actionId = actionId ?: previous?.actionId,
            actionKey = actionKey ?: previous?.actionKey,
            actionFallback = actionFallback ?: previous?.actionFallback,
        )
        set(next)
        return try {
            block(next)
        } finally {
            set(previous)
        }
    }

    fun set(scope: Scope?) {
        if (scope == null) {
            currentScope.remove()
        } else {
            currentScope.set(scope)
        }
    }
}
