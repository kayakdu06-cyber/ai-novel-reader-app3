package app.zhijuan.provider.openai.responses

import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderJsonSchema
import app.zhijuan.provider.common.ProviderRequestField
import app.zhijuan.provider.transport.SensitiveHttpBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class OpenAiResponsesUnsupportedFieldsException(
    val fields: Set<ProviderRequestField>,
) : IllegalArgumentException(
    "OpenAI Responses request contains unsupported fields: " +
        fields.sortedBy(Enum<*>::ordinal).joinToString(","),
)

internal object OpenAiResponsesRequestEncoder {
    private val strictJson = Json { isLenient = false }

    fun encode(
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
        capabilities: ProviderCapabilitySnapshot,
    ): SensitiveHttpBody {
        val unsupported = request.unsupportedFields(profile, capabilities)
        if (unsupported.isNotEmpty()) throw OpenAiResponsesUnsupportedFieldsException(unsupported)
        request.structuredOutputSchema?.let(::validateSchema)

        val output = SensitiveJsonBuffer()
        return try {
            output.ascii("{\"model\":")
            request.modelId.withValue(output::quoted)
            output.ascii(",\"input\":[")
            request.prompt.withParts { writeMessages(output, it) }
            output.ascii("]")
            output.ascii(",\"stream\":")
            output.ascii(if (request.stream) "true" else "false")
            // 织卷 owns conversation state locally; server-side response storage is never required.
            output.ascii(",\"store\":false")
            request.parameters.temperature?.let {
                output.ascii(",\"temperature\":")
                output.ascii(it.toString())
            }
            request.parameters.topP?.let {
                output.ascii(",\"top_p\":")
                output.ascii(it.toString())
            }
            request.parameters.maxOutputTokens?.let {
                output.ascii(",\"max_output_tokens\":")
                output.ascii(it.toString())
            }
            request.parameters.reasoningEffort?.let {
                output.ascii(",\"reasoning\":{\"effort\":")
                output.quoted(it.name.lowercase())
                output.ascii("}")
            }
            request.structuredOutputSchema?.let { schema ->
                output.ascii(",\"text\":{\"format\":{\"type\":\"json_schema\",\"name\":\"zhijuan_stage\",\"strict\":true,\"schema\":")
                schema.withValue(output::rawJson)
                output.ascii("}}")
            }
            output.ascii("}")
            output.toSensitiveBody()
        } finally {
            output.close()
        }
    }

    private fun writeMessages(output: SensitiveJsonBuffer, parts: List<PromptPart>) {
        val system = parts.filter { it.layer == PromptLayer.APPLICATION_HARD_RULES }
        val developer = parts.filter {
            it.layer != PromptLayer.APPLICATION_HARD_RULES && it.layer != PromptLayer.USER_REQUEST
        }
        val user = parts.filter { it.layer == PromptLayer.USER_REQUEST }
        var needsComma = false
        listOf("system" to system, "developer" to developer, "user" to user).forEach { (role, grouped) ->
            if (grouped.isEmpty()) return@forEach
            if (needsComma) output.ascii(",")
            writeMessage(output, role, grouped)
            needsComma = true
        }
        check(needsComma) { "OpenAI Responses input cannot be empty." }
    }

    private fun writeMessage(
        output: SensitiveJsonBuffer,
        role: String,
        parts: List<PromptPart>,
    ) {
        output.ascii("{\"role\":")
        output.quoted(role)
        output.ascii(",\"content\":")
        output.beginString()
        parts.forEachIndexed { index, part ->
            if (index > 0) output.stringFragment("\n\n")
            output.stringFragment("[")
            output.stringFragment(part.layer.name)
            output.stringFragment("]\n")
            part.content.withValue(output::stringFragment)
        }
        output.endString()
        output.ascii("}")
    }

    private fun validateSchema(schema: ProviderJsonSchema) {
        schema.withValue { value ->
            require(strictJson.parseToJsonElement(value) is JsonObject) {
                "Structured output schema must be a valid JSON object."
            }
        }
    }
}

/** Keeps prompt text out of immutable request-body Strings and clears its backing array. */
private class SensitiveJsonBuffer : AutoCloseable {
    private var bytes = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var closed = false
    private var stringOpen = false

    fun ascii(value: String) {
        check(!closed)
        require(!stringOpen) { "Raw JSON cannot be written inside a JSON string." }
        value.forEach { character ->
            require(character.code in 0x20..0x7e) { "Raw JSON fragment must be ASCII." }
            append(character.code)
        }
    }

    fun rawJson(value: String) {
        check(!closed)
        require(!stringOpen) { "Raw JSON cannot be written inside a JSON string." }
        writeUtf8(value, escape = false)
    }

    fun quoted(value: String) {
        beginString()
        stringFragment(value)
        endString()
    }

    fun beginString() {
        check(!closed)
        check(!stringOpen) { "JSON string is already open." }
        append(QUOTE)
        stringOpen = true
    }

    fun stringFragment(value: String) {
        check(!closed)
        check(stringOpen) { "JSON string is not open." }
        writeUtf8(value, escape = true)
    }

    fun endString() {
        check(!closed)
        check(stringOpen) { "JSON string is not open." }
        append(QUOTE)
        stringOpen = false
    }

    fun toSensitiveBody(): SensitiveHttpBody {
        check(!closed)
        check(!stringOpen) { "JSON string was not closed." }
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
                    writeUnicodeEscape(character)
                    index += 1
                    continue
                }
            }
            val codePoint: Int
            if (character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                codePoint = Character.toCodePoint(character, value[index + 1])
                index += 2
            } else if (character.isSurrogate()) {
                writeUnicodeEscape(character)
                index += 1
                continue
            } else {
                codePoint = character.code
                index += 1
            }
            writeCodePoint(codePoint)
        }
    }

    private fun writeCodePoint(codePoint: Int) {
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

    private fun writeUnicodeEscape(character: Char) {
        append(BACKSLASH)
        append('u'.code)
        for (shift in 12 downTo 0 step 4) append(HEX[(character.code shr shift) and 0xf].code)
    }

    private fun append(value: Int) {
        ensureCapacity(1)
        bytes[size++] = value.toByte()
    }

    private fun ensureCapacity(additional: Int) {
        val required = size + additional
        require(required <= SensitiveHttpBody.MAX_BYTES) { "OpenAI Responses request body is too large." }
        if (required <= bytes.size) return
        var nextSize = bytes.size
        while (nextSize < required) nextSize = minOf(nextSize * 2, SensitiveHttpBody.MAX_BYTES)
        val replacement = ByteArray(nextSize)
        bytes.copyInto(replacement, endIndex = size)
        bytes.fill(0)
        bytes = replacement
    }

    private companion object {
        const val INITIAL_CAPACITY = 1_024
        const val QUOTE = 0x22
        const val BACKSLASH = 0x5c
        const val HEX = "0123456789abcdef"
    }
}
