package app.zhijuan.provider.anthropic

import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderRefusalCategory
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderUsage
import app.zhijuan.provider.common.ProviderUsageQuality
import app.zhijuan.provider.common.SensitiveProviderText
import app.zhijuan.provider.stream.SseItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal object AnthropicJson {
    private val parser = Json { ignoreUnknownKeys = true; isLenient = false }

    fun objectFrom(value: String): JsonObject =
        parser.parseToJsonElement(value) as? JsonObject ?: throw AnthropicProtocolException()

    fun objectFrom(bytes: ByteArray): JsonObject = objectFrom(bytes.decodeToString())
}

internal class AnthropicProtocolException : IllegalArgumentException("Anthropic payload is invalid.")

internal class AnthropicStreamMapper(
    private val structuredOutput: Boolean,
) {
    private var started = false
    private var terminal = false
    private var stopReason: String? = null
    private var refusalExplanation: String? = null
    private val blocks = mutableMapOf<Int, BlockKind>()

    fun accept(item: SseItem): List<ProviderStreamEvent> {
        if (terminal) return emptyList()
        return when (item) {
            is SseItem.Comment -> listOf(ProviderStreamEvent.Heartbeat)
            is SseItem.Event -> acceptEvent(item)
        }
    }

    fun finishAtEof(): List<ProviderStreamEvent> {
        if (terminal) return emptyList()
        terminal = true
        return listOf(ProviderStreamEvent.Failed(StandardErrorCode.STREAM_INTERRUPTED))
    }

    fun isTerminal() = terminal

    private fun acceptEvent(item: SseItem.Event): List<ProviderStreamEvent> {
        val root = runCatching { AnthropicJson.objectFrom(item.value.data) }
            .getOrElse { return fail(StandardErrorCode.PROTOCOL_MISMATCH) }
        val type = root.stringValue("type")?.takeIf(String::isNotBlank)
            ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        if (item.value.event != "message" && item.value.event != type) {
            return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        }
        return when (type) {
            "message_start" -> onMessageStart(root)
            "content_block_start" -> onBlockStart(root)
            "content_block_delta" -> onBlockDelta(root)
            "content_block_stop" -> onBlockStop(root)
            "message_delta" -> onMessageDelta(root)
            "message_stop" -> onMessageStop()
            "ping" -> listOf(ProviderStreamEvent.Heartbeat)
            "error" -> fail(AnthropicErrorMapper.map(null, root.objectValue("error")))
            else -> emptyList()
        }
    }

    private fun onMessageStart(root: JsonObject): List<ProviderStreamEvent> {
        if (started) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        val message = root.objectValue("message") ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        if (message.stringValue("type") != "message") return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        started = true
        return message.objectValue("usage")?.let(::parseAnthropicUsage)?.let {
            listOf(ProviderStreamEvent.UsageUpdate(it))
        }.orEmpty()
    }

    private fun onBlockStart(root: JsonObject): List<ProviderStreamEvent> {
        if (!started) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        val index = root.indexValue() ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        if (blocks.containsKey(index)) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        val block = root.objectValue("content_block") ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        val kind = when (block.stringValue("type")) {
            "text" -> BlockKind.TEXT
            "thinking", "redacted_thinking" -> BlockKind.THINKING
            "tool_use", "server_tool_use" -> BlockKind.TOOL
            else -> BlockKind.UNKNOWN
        }
        blocks[index] = kind
        val initialText = block.stringValue("text")
        return if (kind == BlockKind.TEXT && !initialText.isNullOrEmpty()) {
            listOf(contentEvent(initialText, structuredOutput))
        } else {
            emptyList()
        }
    }

    private fun onBlockDelta(root: JsonObject): List<ProviderStreamEvent> {
        if (!started) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        val index = root.indexValue() ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        val kind = blocks[index] ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        val delta = root.objectValue("delta") ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        return when (delta.stringValue("type")) {
            "text_delta" -> {
                if (kind != BlockKind.TEXT) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
                val text = delta.stringValue("text") ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
                if (text.isEmpty()) emptyList() else listOf(contentEvent(text, structuredOutput))
            }
            "thinking_delta", "signature_delta" -> {
                if (kind != BlockKind.THINKING) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
                emptyList()
            }
            "input_json_delta" -> {
                if (kind != BlockKind.TOOL) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun onBlockStop(root: JsonObject): List<ProviderStreamEvent> {
        if (!started) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        val index = root.indexValue() ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        if (blocks.remove(index) == null) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        return emptyList()
    }

    private fun onMessageDelta(root: JsonObject): List<ProviderStreamEvent> {
        if (!started) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        val delta = root.objectValue("delta") ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        delta.stringValue("stop_reason")?.let { incoming ->
            val previous = stopReason
            if (previous != null && previous != incoming) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
            stopReason = incoming
        }
        delta.objectValue("stop_details")?.stringValue("explanation")?.let {
            refusalExplanation = it.take(MAX_REFUSAL_CHARACTERS)
        }
        return root.objectValue("usage")?.let(::parseAnthropicUsage)?.let {
            listOf(ProviderStreamEvent.UsageUpdate(it))
        }.orEmpty()
    }

    private fun onMessageStop(): List<ProviderStreamEvent> {
        if (!started || blocks.isNotEmpty()) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        terminal = true
        return listOf(terminalEvent(stopReason, refusalExplanation))
    }

    private fun fail(code: StandardErrorCode): List<ProviderStreamEvent> {
        terminal = true
        return listOf(ProviderStreamEvent.Failed(code))
    }

    private enum class BlockKind { TEXT, THINKING, TOOL, UNKNOWN }
}

internal object AnthropicNonStreamingMapper {
    fun map(root: JsonObject, structuredOutput: Boolean): List<ProviderStreamEvent> {
        if (root.stringValue("type") == "error") {
            return listOf(ProviderStreamEvent.Failed(AnthropicErrorMapper.map(null, root.objectValue("error"))))
        }
        if (root.stringValue("type") != "message") {
            return listOf(ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH))
        }
        val output = mutableListOf<ProviderStreamEvent>()
        root.arrayValue("content").orEmpty().forEach { element ->
            val block = element as? JsonObject ?: return@forEach
            if (block.stringValue("type") == "text") {
                block.stringValue("text")?.takeIf(String::isNotEmpty)?.let {
                    output += contentEvent(it, structuredOutput)
                }
            }
        }
        root.objectValue("usage")?.let(::parseAnthropicUsage)?.let {
            output += ProviderStreamEvent.UsageUpdate(it)
        }
        output += terminalEvent(
            root.stringValue("stop_reason"),
            root.objectValue("stop_details")?.stringValue("explanation")?.take(MAX_REFUSAL_CHARACTERS),
        )
        return output
    }
}

internal object AnthropicModelListMapper {
    fun map(root: JsonObject): List<String> {
        val data = root.arrayValue("data") ?: throw AnthropicProtocolException()
        return data.mapNotNull { (it as? JsonObject)?.stringValue("id")?.takeIf(String::isNotBlank) }
            .distinct()
    }
}

internal object AnthropicErrorMapper {
    fun map(statusCode: Int?, error: JsonObject?): StandardErrorCode {
        val type = error?.stringValue("type")?.lowercase().orEmpty()
        return when {
            type == "authentication_error" || type == "permission_error" -> StandardErrorCode.AUTH_FAILED
            type == "billing_error" -> StandardErrorCode.QUOTA_EXHAUSTED
            type == "rate_limit_error" -> StandardErrorCode.RATE_LIMITED
            type == "overloaded_error" || type == "api_error" || type == "timeout_error" -> {
                StandardErrorCode.SERVER_OVERLOADED
            }
            type == "request_too_large" -> StandardErrorCode.CONTEXT_TOO_LARGE
            type == "invalid_request_error" || type == "not_found_error" -> StandardErrorCode.PROTOCOL_MISMATCH
            statusCode == 401 || statusCode == 403 -> StandardErrorCode.AUTH_FAILED
            statusCode == 402 -> StandardErrorCode.QUOTA_EXHAUSTED
            statusCode == 413 -> StandardErrorCode.CONTEXT_TOO_LARGE
            statusCode == 429 -> StandardErrorCode.RATE_LIMITED
            statusCode in setOf(500, 504, 529) -> StandardErrorCode.SERVER_OVERLOADED
            statusCode in setOf(400, 404, 405, 415, 422) -> StandardErrorCode.PROTOCOL_MISMATCH
            else -> StandardErrorCode.UNKNOWN_RESULT
        }
    }
}

internal fun parseAnthropicErrorObject(bytes: ByteArray): JsonObject? = runCatching {
    AnthropicJson.objectFrom(bytes).objectValue("error")
}.getOrNull()

private fun terminalEvent(reason: String?, refusal: String?): ProviderStreamEvent = when (reason) {
    "end_turn", "stop_sequence" -> ProviderStreamEvent.Completed(ProviderFinishReason.STOP)
    "max_tokens" -> ProviderStreamEvent.Completed(ProviderFinishReason.LENGTH)
    "tool_use" -> ProviderStreamEvent.Completed(ProviderFinishReason.TOOL_CALL)
    "refusal" -> ProviderStreamEvent.Refused(
        ProviderRefusalCategory.POLICY,
        refusal?.takeIf(String::isNotBlank)?.let(SensitiveProviderText::from),
    )
    "model_context_window_exceeded" -> ProviderStreamEvent.Failed(StandardErrorCode.CONTEXT_TOO_LARGE)
    "pause_turn" -> ProviderStreamEvent.Failed(StandardErrorCode.UNKNOWN_RESULT)
    null -> ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH)
    else -> ProviderStreamEvent.Failed(StandardErrorCode.UNKNOWN_RESULT)
}

private fun contentEvent(value: String, structuredOutput: Boolean): ProviderStreamEvent =
    if (structuredOutput) ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from(value))
    else ProviderStreamEvent.TextDelta(SensitiveProviderText.from(value))

private fun parseAnthropicUsage(root: JsonObject): ProviderUsage? {
    val input = root.nonNegativeLong("input_tokens")
    val output = root.nonNegativeLong("output_tokens")
    val cacheRead = root.nonNegativeLong("cache_read_input_tokens")
    val cacheWrite = root.nonNegativeLong("cache_creation_input_tokens")
    val thinking = root.objectValue("output_tokens_details")?.nonNegativeLong("thinking_tokens")
    if (listOf(input, output, cacheRead, cacheWrite, thinking).all { it == null }) return null
    return ProviderUsage(
        inputTokens = input,
        outputTokens = output,
        cachedInputTokens = cacheRead,
        cachedWriteTokens = cacheWrite,
        reasoningTokens = thinking,
        totalTokens = null,
        quality = ProviderUsageQuality.PROVIDER_REPORTED,
    )
}

private fun JsonObject.objectValue(name: String) = this[name] as? JsonObject
private fun JsonObject.arrayValue(name: String) = this[name] as? JsonArray
private fun JsonObject.stringValue(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.indexValue(): Int? {
    val raw = nonNegativeLong("index") ?: return null
    return raw.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

private fun JsonObject.nonNegativeLong(name: String): Long? {
    val element: JsonElement = this[name] ?: return null
    val value = (element as? JsonPrimitive)?.longOrNull ?: throw AnthropicProtocolException()
    if (value < 0) throw AnthropicProtocolException()
    return value
}

private const val MAX_REFUSAL_CHARACTERS = 32_768
