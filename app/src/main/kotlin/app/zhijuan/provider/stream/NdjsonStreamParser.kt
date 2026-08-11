package app.zhijuan.provider.stream

data class NdjsonRecord(
    val json: String,
)

/** Incremental newline-delimited JSON framer. JSON decoding belongs to a provider adapter. */
class NdjsonStreamParser(
    maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
) {
    private val lineFramer = ByteLineFramer(maxLineBytes)
    private var firstLine = true
    private var finished = false

    fun feed(chunk: ByteArray): List<NdjsonRecord> {
        check(!finished) { "Cannot feed a finished NDJSON parser." }
        val output = mutableListOf<NdjsonRecord>()
        lineFramer.feed(chunk) { rawLine -> addLine(normalizeFirstLine(rawLine), output) }
        return output
    }

    fun finish(): List<NdjsonRecord> {
        check(!finished) { "NDJSON parser is already finished." }
        finished = true
        val output = mutableListOf<NdjsonRecord>()
        lineFramer.finish { rawLine -> addLine(normalizeFirstLine(rawLine), output) }
        return output
    }

    private fun addLine(
        line: String,
        output: MutableList<NdjsonRecord>,
    ) {
        if (line.isNotBlank()) {
            output += NdjsonRecord(line)
        }
    }

    private fun normalizeFirstLine(line: String): String {
        if (!firstLine) return line
        firstLine = false
        return line.removePrefix("\uFEFF")
    }

    companion object {
        const val DEFAULT_MAX_LINE_BYTES: Int = 1_048_576
    }
}
