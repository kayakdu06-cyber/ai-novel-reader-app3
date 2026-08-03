package app.zhijuan.provider.openai.chat

import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderJsonSchema
import app.zhijuan.provider.common.ProviderRequestField
import app.zhijuan.provider.common.ReasoningEffort
import app.zhijuan.provider.transport.SensitiveHttpBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class OpenAiChatUnsupportedFieldsException(
    val fields: Set<ProviderRequestField>,
) : IllegalArgumentException(
    "OpenAI Chat request contains unsupported fields: " + fields.sortedBy(Enum<*>::ordinal).joinToString(","),
)

internal object OpenAiChatRequestEncoder {
    private val strictJson = Json {
        isLenient = false
    }

    fun encode(
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
        capabilities: ProviderCapabilitySnapshot,
        policy: OpenAiChatProtocolPolicy,
    ): SensitiveHttpBody {
        val unsupported = request.unsupportedFields(profile, capabilities)
        if (unsupported.isNotEmpty()) throw OpenAiChatUnsupportedFieldsException(unsupported)
        val schema = request.structuredOutputSchema
        schema?.let(::validateSchema)

        val output = SensitiveJsonBuffer()
        return try {
            output.ascii("{\"model\":")
            request.modelId.withValue(output::quoted)
            output.ascii(",\"messages\":[")
            request.prompt.withParts { parts -> writeMessages(output, parts, schema, policy) }
            output.ascii("]")
            output.ascii(",\"stream\":")
            output.ascii(if (request.stream) "true" else "false")
            if (request.stream && policy.includeStreamUsage) {
                output.ascii(",\"stream_options\":{\"include_usage\":true}")
            }
            request.parameters.temperature?.let {
                output.ascii(",\"temperature\":")
                output.ascii(it.toString())
            }
            request.parameters.topP?.let {
                output.ascii(",\"top_p\":")
                output.ascii(it.toString())
            }
            request.parameters.maxOutputTokens?.let { maximum ->
                when (policy.maximumTokenField) {
                    MaximumTokenField.MAX_COMPLETION_TOKENS -> {
                        output.ascii(",\"max_completion_tokens\":")
                        output.ascii(maximum.toString())
                    }
                    MaximumTokenField.MAX_TOKENS -> {
                        output.ascii(",\"max_tokens\":")
                        output.ascii(maximum.toString())
                    }
                    MaximumTokenField.OMIT -> error("Unsupported maximum token field passed capability checks.")
                }
            }
            request.parameters.seed?.let {
                output.ascii(",\"seed\":")
                output.ascii(it.toString())
            }
            request.parameters.reasoningEffort?.let { effort ->
                output.ascii(",\"reasoning_effort\":")
                output.quoted(reasoningValue(effort, policy.mode))
            }
            schema?.let {
                output.ascii(",\"response_format\":")
                writeResponseFormat(output, it, policy.structuredOutputEncoding)
            }
            output.ascii("}")
            output.toSensitiveBody()
        } finally {
            output.close()
        }
    }

    private fun writeMessages(
        output: SensitiveJsonBuffer,
        parts: List<PromptPart>,
        schema: ProviderJsonSchema?,
        policy: OpenAiChatProtocolPolicy,
    ) {
        if (!policy.useSystemMessage) {
            writeMessage(output, "user", parts, null)
            return
        }

        val systemParts = parts.filter { it.layer != PromptLayer.USER_REQUEST }
        val userParts = parts.filter { it.layer == PromptLayer.USER_REQUEST }
        val schemaForPrompt = schema.takeIf {
            policy.structuredOutputEncoding == StructuredOutputEncoding.JSON_OBJECT
        }
        var needsComma = false
        if (systemParts.isNotEmpty() || schemaForPrompt != null) {
            writeMessage(output, "system", systemParts, schemaForPrompt)
            needsComma = true
        }
        if (userParts.isNotEmpty()) {
            if (needsComma) output.ascii(",")
            writeMessage(output, "user", userParts, null)
        }
    }

    private fun writeMessage(
        output: SensitiveJsonBuffer,
        role: String,
        parts: List<PromptPart>,
        schemaForPrompt: ProviderJsonSchema?,
    ) {
        output.ascii("{\"role\":")
        output.quoted(role)
        output.ascii(",\"content\":")
        output.beginString()
        var hasContent = false
        parts.forEach { part ->
            if (hasContent) output.stringFragment("\n\n")
            output.stringFragment("[")
            output.stringFragment(part.layer.name)
            output.stringFragment("]\n")
            part.content.withValue(output::stringFragment)
            hasContent = true
        }
        schemaForPrompt?.let { schema ->
            if (hasContent) output.stringFragment("\n\n")
            output.stringFragment("[STRUCTURED_OUTPUT]\nOnly output one JSON object matching this schema:\n")
            schema.withValue(output::stringFragment)
        }
        output.endString()
        output.ascii("}")
    }

    private fun writeResponseFormat(
        output: SensitiveJsonBuffer,
        schema: ProviderJsonSchema,
        encoding: StructuredOutputEncoding,
    ) {
        when (encoding) {
            StructuredOutputEncoding.JSON_SCHEMA -> {
                output.ascii(
                    "{\"type\":\"json_schema\",\"json_schema\":{" +
                        "\"name\":\"zhijuan_stage\",\"strict\":true,\"schema\":",
                )
                schema.withValue(output::rawJson)
                output.ascii("}}")
            }
            StructuredOutputEncoding.JSON_OBJECT -> {
                output.ascii("{\"type\":\"json_object\"}")
            }
            StructuredOutputEncoding.OMIT -> error("Unsupported structured output passed capability checks.")
        }
    }

    private fun validateSchema(schema: ProviderJsonSchema) {
        schema.withValue { value ->
            require(strictJson.parseToJsonElement(value) is JsonObject) {
                "Structured output schema must be a valid JSON object."
            }
        }
    }

    private fun reasoningValue(
        effort: ReasoningEffort,
        mode: OpenAiChatCompatibilityMode,
    ): String = when (mode) {
        OpenAiChatCompatibilityMode.OPENAI -> effort.name.lowercase()
        OpenAiChatCompatibilityMode.DEEPSEEK -> when (effort) {
            ReasoningEffort.LOW -> "low"
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            -> "high"
        }
        OpenAiChatCompatibilityMode.RELAY_MINIMAL -> error(
            "Unsupported reasoning effort passed capability checks.",
        )
    }
}

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
        val result = bytes.copyOf(size)
        return SensitiveHttpBody.fromBytesAndClear(result)
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
                when (character) {
                    '"' -> {
                        append(BACKSLASH)
                        append(QUOTE)
                        index += 1
                        continue
                    }
                    '\\' -> {
                        append(BACKSLASH)
                        append(BACKSLASH)
                        index += 1
                        continue
                    }
                    '\b' -> {
                        append(BACKSLASH)
                        append('b'.code)
                        index += 1
                        continue
                    }
                    '\u000c' -> {
                        append(BACKSLASH)
                        append('f'.code)
                        index += 1
                        continue
                    }
                    '\n' -> {
                        append(BACKSLASH)
                        append('n'.code)
                        index += 1
                        continue
                    }
                    '\r' -> {
                        append(BACKSLASH)
                        append('r'.code)
                        index += 1
                        continue
                    }
                    '\t' -> {
                        append(BACKSLASH)
                        append('t'.code)
                        index += 1
                        continue
                    }
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
        for (shift in 12 downTo 0 step 4) {
            append(HEX[(character.code shr shift) and 0xf].code)
        }
    }

    private fun append(value: Int) {
        ensureCapacity(1)
        bytes[size] = value.toByte()
        size += 1
    }

    private fun ensureCapacity(additional: Int) {
        val required = size + additional
        require(required <= SensitiveHttpBody.MAX_BYTES) { "OpenAI Chat request body is too large." }
        if (required <= bytes.size) return
        var nextSize = bytes.size
        while (nextSize < required) {
            nextSize = minOf(nextSize * 2, SensitiveHttpBody.MAX_BYTES)
        }
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
