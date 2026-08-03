package app.zhijuan.provider.stream

data class SseEvent(
    val event: String,
    val data: String,
    val id: String?,
    val retryMillis: Long?,
)

sealed interface SseItem {
    data class Event(val value: SseEvent) : SseItem

    /** A colon-prefixed SSE comment, commonly used as a connection heartbeat. */
    data class Comment(val text: String) : SseItem
}

/**
 * Incremental Server-Sent Events framer. Call [feed] for every received byte chunk and [finish]
 * once at EOF. An SSE event is dispatched only after its terminating blank line.
 */
class SseStreamParser(
    maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
) {
    private val lineFramer = ByteLineFramer(maxLineBytes)
    private val dataLines = mutableListOf<String>()
    private var eventName: String? = null
    private var lastEventId: String? = null
    private var reconnectDelayMillis: Long? = null
    private var firstLine = true
    private var finished = false

    fun feed(chunk: ByteArray): List<SseItem> {
        check(!finished) { "Cannot feed a finished SSE parser." }
        val output = mutableListOf<SseItem>()
        lineFramer.feed(chunk) { rawLine -> processLine(normalizeFirstLine(rawLine), output) }
        return output
    }

    fun finish(): List<SseItem> {
        check(!finished) { "SSE parser is already finished." }
        finished = true
        val output = mutableListOf<SseItem>()
        lineFramer.finish { rawLine -> processLine(normalizeFirstLine(rawLine), output) }
        // WHATWG SSE semantics discard an event that was not terminated by a blank line.
        resetPendingEvent()
        return output
    }

    private fun processLine(
        line: String,
        output: MutableList<SseItem>,
    ) {
        if (line.isEmpty()) {
            dispatch(output)
            return
        }
        if (line.startsWith(':')) {
            output += SseItem.Comment(line.drop(1).removePrefix(" "))
            return
        }

        val colonIndex = line.indexOf(':')
        val field = if (colonIndex < 0) line else line.substring(0, colonIndex)
        val rawValue = if (colonIndex < 0) "" else line.substring(colonIndex + 1)
        val value = rawValue.removePrefix(" ")
        when (field) {
            "data" -> dataLines += value
            "event" -> if ('\u0000' !in value) eventName = value
            "id" -> if ('\u0000' !in value) lastEventId = value
            "retry" -> if (value.isNotEmpty() && value.all(Char::isDigit)) {
                reconnectDelayMillis = value.toLongOrNull()
            }
        }
    }

    private fun dispatch(output: MutableList<SseItem>) {
        if (dataLines.isNotEmpty()) {
            output += SseItem.Event(
                SseEvent(
                    event = eventName?.takeIf(String::isNotEmpty) ?: DEFAULT_EVENT_NAME,
                    data = dataLines.joinToString("\n"),
                    id = lastEventId,
                    retryMillis = reconnectDelayMillis,
                ),
            )
        }
        resetPendingEvent()
    }

    private fun resetPendingEvent() {
        dataLines.clear()
        eventName = null
    }

    private fun normalizeFirstLine(line: String): String {
        if (!firstLine) return line
        firstLine = false
        return line.removePrefix("\uFEFF")
    }

    companion object {
        const val DEFAULT_MAX_LINE_BYTES: Int = 1_048_576
        private const val DEFAULT_EVENT_NAME = "message"
    }
}
