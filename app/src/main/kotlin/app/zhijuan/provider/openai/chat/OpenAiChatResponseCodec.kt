package app.zhijuan.provider.openai.chat

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal object OpenAiChatJson {
    val parser = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun objectFrom(value: String): JsonObject =
        parser.parseToJsonElement(value) as? JsonObject
            ?: throw OpenAiChatProtocolException()

    fun objectFrom(bytes: ByteArray): JsonObject = objectFrom(bytes.decodeToString())
}

internal class OpenAiChatProtocolException : IllegalArgumentException("OpenAI Chat response is invalid.")

internal class OpenAiChatStreamMapper(
    private val structuredOutput: Boolean,
) {
    private var terminal = false
    private var pendingFinishReason: String? = null
    private val refusal = StringBuilder()

    fun accept(item: SseItem): List<ProviderStreamEvent> {
        if (terminal) return emptyList()
        return when (item) {
            is SseItem.Comment -> listOf(ProviderStreamEvent.Heartbeat)
            is SseItem.Event -> acceptData(item.value.data)
        }
    }

    fun finishAtEof(): List<ProviderStreamEvent> {
        if (terminal) return emptyList()
        terminal = true
        return listOf(ProviderStreamEvent.Failed(StandardErrorCode.STREAM_INTERRUPTED))
    }

    fun isTerminal(): Boolean = terminal

    private fun acceptData(data: String): List<ProviderStreamEvent> {
        if (data.trim() == "[DONE]") {
            terminal = true
            return listOf(terminalEvent(pendingFinishReason, refusal.takeIf(StringBuilder::isNotEmpty)?.toString()))
        }
        val root = runCatching { OpenAiChatJson.objectFrom(data) }
            .getOrElse { return fail(StandardErrorCode.PROTOCOL_MISMATCH) }
        root.objectValue("error")?.let { error ->
            return fail(OpenAiChatErrorMapper.map(null, error))
        }

        val output = mutableListOf<ProviderStreamEvent>()
        root.objectValue("usage")?.let(::parseUsage)?.let { output += ProviderStreamEvent.UsageUpdate(it) }
        val choice = root.arrayValue("choices")?.choiceZero()
        choice?.objectValue("delta")?.let { delta ->
            delta.stringValue("refusal")?.let(::appendRefusal)
            delta.stringValue("content")?.takeIf(String::isNotEmpty)?.let { fragment ->
                output += contentEvent(fragment, structuredOutput)
            }
        }
        choice?.stringValue("finish_reason")?.let { reason ->
            val previous = pendingFinishReason
            if (previous != null && previous != reason) {
                return fail(StandardErrorCode.PROTOCOL_MISMATCH)
            }
            pendingFinishReason = reason
        }
        return output
    }

    private fun appendRefusal(fragment: String) {
        val remaining = MAX_REFUSAL_CHARACTERS - refusal.length
        if (remaining > 0) refusal.append(fragment, 0, minOf(remaining, fragment.length))
    }

    private fun fail(code: StandardErrorCode): List<ProviderStreamEvent> {
        terminal = true
        return listOf(ProviderStreamEvent.Failed(code))
    }
}

internal object OpenAiChatNonStreamingMapper {
    fun map(
        root: JsonObject,
        structuredOutput: Boolean,
    ): List<ProviderStreamEvent> {
        root.objectValue("error")?.let { error ->
            return listOf(ProviderStreamEvent.Failed(OpenAiChatErrorMapper.map(null, error)))
        }
        val choice = root.arrayValue("choices")?.choiceZero()
            ?: return listOf(ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH))
        val message = choice.objectValue("message")
            ?: return listOf(ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH))
        val refusal = message.stringValue("refusal")
        val output = mutableListOf<ProviderStreamEvent>()
        if (refusal.isNullOrEmpty()) {
            message.stringValue("content")?.takeIf(String::isNotEmpty)?.let { content ->
                output += contentEvent(content, structuredOutput)
            }
        }
        root.objectValue("usage")?.let(::parseUsage)?.let { output += ProviderStreamEvent.UsageUpdate(it) }
        output += terminalEvent(choice.stringValue("finish_reason"), refusal)
        return output
    }
}

internal object OpenAiChatModelListMapper {
    fun map(root: JsonObject): List<String> {
        val models = root.arrayValue("data") ?: throw OpenAiChatProtocolException()
        return models.mapNotNull { element ->
            (element as? JsonObject)?.stringValue("id")?.takeIf(String::isNotBlank)
        }.distinct()
    }
}

internal object OpenAiChatErrorMapper {
    fun map(statusCode: Int?, error: JsonObject?): StandardErrorCode {
        val tokens = buildList {
            error?.stringValue("code")?.let(::add)
            error?.stringValue("type")?.let(::add)
        }.joinToString(" ").lowercase()
        return when {
            "context_length" in tokens || "context_window" in tokens || "max_tokens" in tokens -> {
                StandardErrorCode.CONTEXT_TOO_LARGE
            }
            "model_not_found" in tokens || "model_not_exist" in tokens -> StandardErrorCode.MODEL_NOT_FOUND
            "insufficient_quota" in tokens || "insufficient_balance" in tokens ||
                "billing" in tokens || "quota_exhausted" in tokens -> StandardErrorCode.QUOTA_EXHAUSTED
            "rate_limit" in tokens -> StandardErrorCode.RATE_LIMITED
            "invalid_api_key" in tokens || "authentication" in tokens || "unauthorized" in tokens -> {
                StandardErrorCode.AUTH_FAILED
            }
            "content_filter" in tokens || "safety" in tokens || "policy" in tokens -> {
                StandardErrorCode.POLICY_REFUSAL
            }
            statusCode == 401 || statusCode == 403 -> StandardErrorCode.AUTH_FAILED
            statusCode == 402 -> StandardErrorCode.QUOTA_EXHAUSTED
            statusCode == 429 -> StandardErrorCode.RATE_LIMITED
            statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504 -> {
                StandardErrorCode.SERVER_OVERLOADED
            }
            statusCode == 400 || statusCode == 404 || statusCode == 405 || statusCode == 415 ||
                statusCode == 422 -> StandardErrorCode.PROTOCOL_MISMATCH
            statusCode != null -> StandardErrorCode.UNKNOWN_RESULT
            else -> StandardErrorCode.PROTOCOL_MISMATCH
        }
    }
}

internal fun parseErrorObject(bytes: ByteArray): JsonObject? = runCatching {
    OpenAiChatJson.objectFrom(bytes).objectValue("error")
}.getOrNull()

private fun contentEvent(value: String, structuredOutput: Boolean): ProviderStreamEvent =
    if (structuredOutput) {
        ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from(value))
    } else {
        ProviderStreamEvent.TextDelta(SensitiveProviderText.from(value))
    }

private fun terminalEvent(
    finishReason: String?,
    refusal: String?,
): ProviderStreamEvent {
    if (!refusal.isNullOrBlank()) {
        return ProviderStreamEvent.Refused(
            category = ProviderRefusalCategory.SAFETY,
            userFacingMessage = SensitiveProviderText.from(refusal),
        )
    }
    return when (finishReason) {
        "stop" -> ProviderStreamEvent.Completed(ProviderFinishReason.STOP)
        "length" -> ProviderStreamEvent.Completed(ProviderFinishReason.LENGTH)
        "tool_calls",
        "function_call",
        -> ProviderStreamEvent.Completed(ProviderFinishReason.TOOL_CALL)
        "content_filter" -> ProviderStreamEvent.Refused(ProviderRefusalCategory.SAFETY)
        "insufficient_system_resource" -> ProviderStreamEvent.Failed(StandardErrorCode.SERVER_OVERLOADED)
        null -> ProviderStreamEvent.Failed(StandardErrorCode.STREAM_INTERRUPTED)
        else -> ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH)
    }
}

private fun parseUsage(root: JsonObject): ProviderUsage? {
    val input = root.longValue("prompt_tokens")
    val output = root.longValue("completion_tokens")
    val total = root.longValue("total_tokens")
    val promptDetails = root.objectValue("prompt_tokens_details")
    val completionDetails = root.objectValue("completion_tokens_details")
    val cachedInput = root.longValue("prompt_cache_hit_tokens")
        ?: promptDetails?.longValue("cached_tokens")
    val cachedWrite = promptDetails?.longValue("cache_write_tokens")
    val reasoning = completionDetails?.longValue("reasoning_tokens")
    if (listOf(input, output, total, cachedInput, cachedWrite, reasoning).all { it == null }) return null
    return try {
        ProviderUsage(
            inputTokens = input,
            outputTokens = output,
            cachedInputTokens = cachedInput,
            cachedWriteTokens = cachedWrite,
            reasoningTokens = reasoning,
            totalTokens = total,
            quality = ProviderUsageQuality.PROVIDER_REPORTED,
        )
    } catch (_: IllegalArgumentException) {
        throw OpenAiChatProtocolException()
    }
}

private fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.arrayValue(name: String): JsonArray? = this[name] as? JsonArray

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longValue(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull?.also {
        if (it < 0) throw OpenAiChatProtocolException()
    }

private fun JsonArray.choiceZero(): JsonObject? =
    mapNotNull { it as? JsonObject }.firstOrNull { choice -> choice.longValue("index") == 0L }
        ?: firstOrNull() as? JsonObject

private const val MAX_REFUSAL_CHARACTERS = 32_768
