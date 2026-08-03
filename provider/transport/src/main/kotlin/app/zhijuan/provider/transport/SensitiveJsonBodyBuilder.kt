package app.zhijuan.provider.transport

/** Builds a UTF-8 JSON body without materializing prompt text as an immutable body String. */
class SensitiveJsonBodyBuilder : AutoCloseable {
    private var bytes = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var closed = false
    private var stringOpen = false

    fun ascii(value: String) {
        check(!closed)
        require(!stringOpen)
        value.forEach {
            require(it.code in 0x20..0x7e)
            append(it.code)
        }
    }

    fun rawJson(value: String) {
        check(!closed)
        require(!stringOpen)
        writeUtf8(value, escape = false)
    }

    fun quoted(value: String) {
        beginString()
        stringFragment(value)
        endString()
    }

    fun beginString() {
        check(!closed && !stringOpen)
        append(QUOTE)
        stringOpen = true
    }

    fun stringFragment(value: String) {
        check(!closed && stringOpen)
        writeUtf8(value, escape = true)
    }

    fun endString() {
        check(!closed && stringOpen)
        append(QUOTE)
        stringOpen = false
    }

    fun toSensitiveBody(): SensitiveHttpBody {
        check(!closed && !stringOpen)
        return SensitiveHttpBody.fromBytesAndClear(bytes.copyOf(size))
    }

    override fun close() {
        if (closed) return
        closed = true
        bytes.fill(0)
        size = 0
        stringOpen = false
    }

    private fun writeUtf8(value: String, escape: Boolean) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (escape) {
                val escaped = when (character) {
                    '"' -> '"'
                    '\\' -> '\\'
                    '\b' -> 'b'
                    '\u000c' -> 'f'
                    '\n' -> 'n'
                    '\r' -> 'r'
                    '\t' -> 't'
                    else -> null
                }
                if (escaped != null) {
                    append(BACKSLASH)
                    append(escaped.code)
                    index += 1
                    continue
                }
                if (character.code < 0x20) {
                    unicodeEscape(character)
                    index += 1
                    continue
                }
            }
            val codePoint: Int
            if (character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                codePoint = Character.toCodePoint(character, value[index + 1])
                index += 2
            } else if (character.isSurrogate()) {
                unicodeEscape(character)
                index += 1
                continue
            } else {
                codePoint = character.code
                index += 1
            }
            when {
                codePoint <= 0x7f -> append(codePoint)
                codePoint <= 0x7ff -> {
                    append(0xc0 or (codePoint shr 6))
                    append(0x80 or (codePoint and 0x3f))
                }
                codePoint <= 0xffff -> {
                    append(0xe0 or (codePoint shr 12))
                    append(0x80 or ((codePoint shr 6) and 0x3f))
                    append(0x80 or (codePoint and 0x3f))
                }
                else -> {
                    append(0xf0 or (codePoint shr 18))
                    append(0x80 or ((codePoint shr 12) and 0x3f))
                    append(0x80 or ((codePoint shr 6) and 0x3f))
                    append(0x80 or (codePoint and 0x3f))
                }
            }
        }
    }

    private fun unicodeEscape(character: Char) {
        append(BACKSLASH)
        append('u'.code)
        for (shift in 12 downTo 0 step 4) append(HEX[(character.code shr shift) and 0xf].code)
    }

    private fun append(value: Int) {
        val required = size + 1
        require(required <= SensitiveHttpBody.MAX_BYTES) { "Sensitive JSON body is too large." }
        if (required > bytes.size) {
            val replacement = ByteArray(minOf(bytes.size * 2, SensitiveHttpBody.MAX_BYTES))
            bytes.copyInto(replacement, endIndex = size)
            bytes.fill(0)
            bytes = replacement
        }
        bytes[size++] = value.toByte()
    }

    private companion object {
        const val INITIAL_CAPACITY = 1_024
        const val QUOTE = 0x22
        const val BACKSLASH = 0x5c
        const val HEX = "0123456789abcdef"
    }
}
