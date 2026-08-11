package app.zhijuan.feature.generation

import app.zhijuan.core.task.ChapterDraftContinuationPolicyV1
import app.zhijuan.provider.common.ProviderFinishReason

enum class ProviderPayloadCompletion {
    COMPLETE,
    TRUNCATED_SAFE_PREFIX,
    INVALID,
}

interface ProviderStreamPayloadDecoder {
    fun onTextDelta(value: String): String

    fun onStructuredDelta(value: String): String

    fun complete(reason: ProviderFinishReason): ProviderPayloadCompletion
}

object PassthroughProviderStreamPayloadDecoder : ProviderStreamPayloadDecoder {
    override fun onTextDelta(value: String): String = value

    override fun onStructuredDelta(value: String): String = value

    override fun complete(reason: ProviderFinishReason): ProviderPayloadCompletion = when (reason) {
        ProviderFinishReason.STOP -> ProviderPayloadCompletion.COMPLETE
        ProviderFinishReason.LENGTH -> ProviderPayloadCompletion.TRUNCATED_SAFE_PREFIX
        else -> ProviderPayloadCompletion.INVALID
    }
}

/**
 * Incrementally decodes the deliberately tiny `chapter-draft.v1` envelope:
 * `{ "body": "..." }`. Only decoded body text reaches the protected draft.
 */
class ChapterDraftV1StreamPayloadDecoder(
    expectedContinuationAnchor: String? = null,
    initialUtf8Bytes: Int = 0,
) : ProviderStreamPayloadDecoder {
    private enum class State {
        LEADING,
        AFTER_OPEN,
        KEY,
        AFTER_KEY,
        BEFORE_BODY,
        BODY,
        ESCAPE,
        UNICODE_ESCAPE,
        AFTER_BODY,
        COMPLETE,
        INVALID,
    }

    private var state = State.LEADING
    private var keyIndex = 0
    private var unicodeDigits = 0
    private var unicodeValue = 0
    private var pendingHighSurrogate: Char? = null
    private val expectedAnchor = expectedContinuationAnchor
    private var anchorIndex = 0
    private var bodyStarted = false
    private var emittedCodePoints = 0
    private var emittedUtf8Bytes = initialUtf8Bytes
    private var completion: ProviderPayloadCompletion? = null

    init {
        require(expectedContinuationAnchor == null || expectedContinuationAnchor.isNotEmpty())
        require(initialUtf8Bytes in 0..ChapterDraftContinuationPolicyV1.MAXIMUM_CHAPTER_UTF8_BYTES)
    }

    override fun onTextDelta(value: String): String {
        invalidate()
        return ""
    }

    override fun onStructuredDelta(value: String): String {
        if (completion != null || state == State.INVALID || value.isEmpty()) return ""
        val output = StringBuilder()
        value.forEach { character -> consume(character, output) }
        return output.toString()
    }

    override fun complete(reason: ProviderFinishReason): ProviderPayloadCompletion {
        completion?.let { return it }
        val anchorComplete = expectedAnchor == null || anchorIndex == expectedAnchor.length
        val hasNovelBody = emittedCodePoints > 0
        val result = when (reason) {
            ProviderFinishReason.STOP -> if (
                state == State.COMPLETE && pendingHighSurrogate == null && anchorComplete && hasNovelBody
            ) {
                ProviderPayloadCompletion.COMPLETE
            } else {
                ProviderPayloadCompletion.INVALID
            }
            ProviderFinishReason.LENGTH -> if (
                bodyStarted && state !in setOf(State.INVALID, State.LEADING, State.AFTER_OPEN, State.KEY) &&
                anchorComplete && hasNovelBody
            ) {
                ProviderPayloadCompletion.TRUNCATED_SAFE_PREFIX
            } else {
                ProviderPayloadCompletion.INVALID
            }
            else -> ProviderPayloadCompletion.INVALID
        }
        completion = result
        return result
    }

    private fun consume(character: Char, output: StringBuilder) {
        if (state == State.INVALID) return
        when (state) {
            State.LEADING -> if (character.isJsonWhitespace()) state = State.LEADING else if (character == '{') {
                state = State.AFTER_OPEN
            } else invalidate()
            State.AFTER_OPEN -> if (character.isJsonWhitespace()) state = State.AFTER_OPEN else if (character == '"') {
                keyIndex = 0
                state = State.KEY
            } else invalidate()
            State.KEY -> {
                val expected = BODY_KEY_WITH_QUOTE.getOrNull(keyIndex)
                if (expected == null || character != expected) {
                    invalidate()
                } else {
                    keyIndex += 1
                    if (keyIndex == BODY_KEY_WITH_QUOTE.length) state = State.AFTER_KEY
                }
            }
            State.AFTER_KEY -> if (character.isJsonWhitespace()) state = State.AFTER_KEY else if (character == ':') {
                state = State.BEFORE_BODY
            } else invalidate()
            State.BEFORE_BODY -> if (character.isJsonWhitespace()) state = State.BEFORE_BODY else if (character == '"') {
                bodyStarted = true
                state = State.BODY
            } else invalidate()
            State.BODY -> when {
                character == '"' -> {
                    if (pendingHighSurrogate != null) invalidate() else state = State.AFTER_BODY
                }
                character == '\\' -> state = State.ESCAPE
                character.code < 0x20 -> invalidate()
                else -> acceptDecoded(character, output)
            }
            State.ESCAPE -> when (character) {
                '"', '\\', '/' -> {
                    acceptDecoded(character, output)
                    if (state != State.INVALID) state = State.BODY
                }
                'b' -> acceptEscape('\b', output)
                'f' -> acceptEscape('\u000c', output)
                'n' -> acceptEscape('\n', output)
                'r' -> acceptEscape('\r', output)
                't' -> acceptEscape('\t', output)
                'u' -> {
                    unicodeDigits = 0
                    unicodeValue = 0
                    state = State.UNICODE_ESCAPE
                }
                else -> invalidate()
            }
            State.UNICODE_ESCAPE -> {
                val digit = character.digitToIntOrNull(16)
                if (digit == null) {
                    invalidate()
                } else {
                    unicodeValue = unicodeValue * 16 + digit
                    unicodeDigits += 1
                    if (unicodeDigits == 4) {
                        acceptDecoded(unicodeValue.toChar(), output)
                        if (state != State.INVALID) state = State.BODY
                    }
                }
            }
            State.AFTER_BODY -> if (character.isJsonWhitespace()) state = State.AFTER_BODY else if (character == '}') {
                state = State.COMPLETE
            } else invalidate()
            State.COMPLETE -> if (!character.isJsonWhitespace()) invalidate()
            State.INVALID -> Unit
        }
    }

    private fun acceptEscape(character: Char, output: StringBuilder) {
        acceptDecoded(character, output)
        if (state != State.INVALID) state = State.BODY
    }

    private fun acceptDecoded(character: Char, output: StringBuilder) {
        val pending = pendingHighSurrogate
        when {
            pending != null && character.isLowSurrogate() -> {
                pendingHighSurrogate = null
                emitDecoded("$pending$character", output)
            }
            pending != null -> invalidate()
            character.isHighSurrogate() -> pendingHighSurrogate = character
            character.isLowSurrogate() -> invalidate()
            else -> emitDecoded(character.toString(), output)
        }
    }

    private fun emitDecoded(value: String, output: StringBuilder) {
        val anchor = expectedAnchor
        if (anchor != null && anchorIndex < anchor.length) {
            value.forEach { character ->
                if (anchor.getOrNull(anchorIndex) != character) {
                    invalidate()
                    return
                }
                anchorIndex += 1
            }
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8).size
        val nextBytes = runCatching { Math.addExact(emittedUtf8Bytes, bytes) }.getOrNull()
        if (nextBytes == null || nextBytes > ChapterDraftContinuationPolicyV1.MAXIMUM_CHAPTER_UTF8_BYTES) {
            invalidate()
            return
        }
        emittedUtf8Bytes = nextBytes
        emittedCodePoints = Math.addExact(emittedCodePoints, value.codePointCount(0, value.length))
        output.append(value)
    }

    private fun invalidate() {
        state = State.INVALID
        pendingHighSurrogate = null
    }

    private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\n' || this == '\r' || this == '\t'

    private companion object {
        const val BODY_KEY_WITH_QUOTE = "body\""
    }
}
