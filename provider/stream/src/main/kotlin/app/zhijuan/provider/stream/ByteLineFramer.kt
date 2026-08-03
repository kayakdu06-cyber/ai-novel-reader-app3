package app.zhijuan.provider.stream

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Splits byte chunks into UTF-8 lines without decoding incomplete multi-byte characters.
 * LF, CRLF and isolated CR are all accepted as line endings.
 */
internal class ByteLineFramer(
    private val maxLineBytes: Int,
) {
    private val line = ByteArrayOutputStream()
    private var previousWasCarriageReturn = false
    private var finished = false

    init {
        require(maxLineBytes > 0) { "maxLineBytes must be positive." }
    }

    fun feed(
        chunk: ByteArray,
        onLine: (String) -> Unit,
    ) {
        check(!finished) { "Cannot feed a finished stream parser." }
        chunk.forEach { byte ->
            if (previousWasCarriageReturn) {
                previousWasCarriageReturn = false
                if (byte == LINE_FEED) {
                    return@forEach
                }
            }

            when (byte) {
                LINE_FEED -> emitLine(onLine)
                CARRIAGE_RETURN -> {
                    emitLine(onLine)
                    previousWasCarriageReturn = true
                }

                else -> {
                    if (line.size() >= maxLineBytes) {
                        throw MalformedStreamException(
                            "Stream line exceeds the $maxLineBytes-byte safety limit.",
                        )
                    }
                    line.write(byte.toInt())
                }
            }
        }
    }

    fun finish(onLine: (String) -> Unit) {
        check(!finished) { "Stream parser is already finished." }
        finished = true
        previousWasCarriageReturn = false
        if (line.size() > 0) {
            emitLine(onLine)
        }
    }

    private fun emitLine(onLine: (String) -> Unit) {
        val bytes = line.toByteArray()
        line.reset()
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: Exception) {
            throw MalformedStreamException("Stream contains malformed UTF-8.", error)
        }
        onLine(decoded)
    }

    private companion object {
        const val LINE_FEED: Byte = 0x0A
        const val CARRIAGE_RETURN: Byte = 0x0D
    }
}
