package app.zhijuan.provider.openai.responses

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

internal object OpenAiResponsesJson {
    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun objectFrom(value: String): JsonObject =
        parser.parseToJsonElement(value) as? JsonObject ?: throw OpenAiResponsesProtocolException()

    fun objectFrom(bytes: ByteArray): JsonObject = objectFrom(bytes.decodeToString())
}

internal class OpenAiResponsesProtocolException :
    IllegalArgumentException("OpenAI Responses payload is invalid.")

internal class OpenAiResponsesStreamMapper(
    private val structuredOutput: Boolean,
) {
    private var terminal = false
    private var sawText = false
    private var lastSequenceNumber: Long? = null
    private val refusal = StringBuilder()

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

    fun isTerminal(): Boolean = terminal

    private fun acceptEvent(item: SseItem.Event): List<ProviderStreamEvent> {
        val root = runCatching { OpenAiResponsesJson.objectFrom(item.value.data) }
            .getOrElse { return fail(StandardErrorCode.PROTOCOL_MISMATCH) }
        val type = root.stringValue("type")?.takeIf(String::isNotBlank)
            ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        if (item.value.event != "message" && item.value.event != type) {
            return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        }
        if (!acceptSequence(root)) return fail(StandardErrorCode.PROTOCOL_MISMATCH)

        return when (type) {
            "response.output_text.delta" -> {
                val delta = root.stringValue("delta") ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
                if (delta.isEmpty()) emptyList() else {
                    sawText = true
                    listOf(contentEvent(delta, structuredOutput))
                }
            }
            "response.output_text.done" -> {
                val text = root.stringValue("text") ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
                if (sawText || text.isEmpty()) emptyList() else {
                    sawText = true
                    listOf(contentEvent(text, structuredOutput))
                }
            }
            "response.refusal.delta" -> {
                val delta = root.stringValue("delta") ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
                appendRefusal(delta)
                emptyList()
            }
            "response.refusal.done" -> {
                if (refusal.isEmpty()) appendRefusal(root.stringValue("refusal").orEmpty())
                emptyList()
            }
            "response.completed" -> terminalFromResponse(root.objectValue("response"), CompletionKind.COMPLETED)
            "response.incomplete" -> terminalFromResponse(root.objectValue("response"), CompletionKind.INCOMPLETE)
            "response.failed" -> terminalFromResponse(root.objectValue("response"), CompletionKind.FAILED)
            "error" -> fail(OpenAiResponsesErrorMapper.map(null, root))
            // The API explicitly permits new event types. Known lifecycle and item events carry no new text.
            else -> emptyList()
        }
    }

    private fun acceptSequence(root: JsonObject): Boolean {
        val element = root["sequence_number"] ?: return true
        val current = (element as? JsonPrimitive)?.longOrNull ?: return false
        if (current < 0) return false
        val previous = lastSequenceNumber
        if (previous != null && current <= previous) return false
        lastSequenceNumber = current
        return true
    }

    private fun terminalFromResponse(
        response: JsonObject?,
        kind: CompletionKind,
    ): List<ProviderStreamEvent> {
        response ?: return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        val expectedStatus = when (kind) {
            CompletionKind.COMPLETED -> "completed"
            CompletionKind.INCOMPLETE -> "incomplete"
            CompletionKind.FAILED -> "failed"
        }
        if (response.stringValue("status") != expectedStatus) {
            return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        }
        val output = mutableListOf<ProviderStreamEvent>()
        val finalContent = parseOutput(response)
        if (!sawText) {
            finalContent.text.forEach { value ->
                if (value.isNotEmpty()) {
                    sawText = true
                    output += contentEvent(value, structuredOutput)
                }
            }
        }
        if (refusal.isEmpty()) finalContent.refusal.forEach(::appendRefusal)
        response.objectValue("usage")?.let(::parseResponsesUsage)?.let {
            output += ProviderStreamEvent.UsageUpdate(it)
        }
        terminal = true
        output += when (kind) {
            CompletionKind.COMPLETED -> {
                if (refusal.isNotEmpty()) refusalEvent(refusal.toString())
                else ProviderStreamEvent.Completed(ProviderFinishReason.STOP)
            }
            CompletionKind.INCOMPLETE -> incompleteEvent(response, refusal.toString())
            CompletionKind.FAILED -> ProviderStreamEvent.Failed(
                OpenAiResponsesErrorMapper.map(null, response.objectValue("error")),
            )
        }
        return output
    }

    private fun appendRefusal(value: String) {
        val remaining = MAX_REFUSAL_CHARACTERS - refusal.length
        if (remaining > 0) refusal.append(value, 0, minOf(remaining, value.length))
    }

    private fun fail(code: StandardErrorCode): List<ProviderStreamEvent> {
        terminal = true
        return listOf(ProviderStreamEvent.Failed(code))
    }
}

internal object OpenAiResponsesNonStreamingMapper {
    fun map(root: JsonObject, structuredOutput: Boolean): List<ProviderStreamEvent> {
        root.objectValue("error")?.takeIf { root.stringValue("status") == null }?.let {
            return listOf(ProviderStreamEvent.Failed(OpenAiResponsesErrorMapper.map(null, it)))
        }
        val output = mutableListOf<ProviderStreamEvent>()
        val content = parseOutput(root)
        content.text.forEach { value ->
            if (value.isNotEmpty()) output += contentEvent(value, structuredOutput)
        }
        root.objectValue("usage")?.let(::parseResponsesUsage)?.let {
            output += ProviderStreamEvent.UsageUpdate(it)
        }
        output += when (root.stringValue("status")) {
            "completed" -> if (content.refusal.isNotEmpty()) {
                refusalEvent(content.refusal.joinToString("").take(MAX_REFUSAL_CHARACTERS))
            } else {
                ProviderStreamEvent.Completed(ProviderFinishReason.STOP)
            }
            "incomplete" -> incompleteEvent(root, content.refusal.joinToString(""))
            "failed" -> ProviderStreamEvent.Failed(
                OpenAiResponsesErrorMapper.map(null, root.objectValue("error")),
            )
            else -> ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH)
        }
        return output
    }
}

internal object OpenAiResponsesModelListMapper {
    fun map(root: JsonObject): List<String> {
        val models = root.arrayValue("data") ?: throw OpenAiResponsesProtocolException()
        return models.mapNotNull { (it as? JsonObject)?.stringValue("id")?.takeIf(String::isNotBlank) }
            .distinct()
    }
}

internal object OpenAiResponsesErrorMapper {
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
            "insufficient_quota" in tokens || "billing" in tokens || "quota_exhausted" in tokens -> {
                StandardErrorCode.QUOTA_EXHAUSTED
            }
            "rate_limit" in tokens -> StandardErrorCode.RATE_LIMITED
            "invalid_api_key" in tokens || "authentication" in tokens || "unauthorized" in tokens -> {
                StandardErrorCode.AUTH_FAILED
            }
            "content_filter" in tokens || "safety" in tokens || "policy" in tokens -> {
                StandardErrorCode.POLICY_REFUSAL
            }
            "server_error" in tokens || "overloaded" in tokens -> StandardErrorCode.SERVER_OVERLOADED
            statusCode == 401 || statusCode == 403 -> StandardErrorCode.AUTH_FAILED
            statusCode == 402 -> StandardErrorCode.QUOTA_EXHAUSTED
            statusCode == 429 -> StandardErrorCode.RATE_LIMITED
            statusCode in setOf(500, 502, 503, 504) -> StandardErrorCode.SERVER_OVERLOADED
            statusCode in setOf(400, 404, 405, 415, 422) -> StandardErrorCode.PROTOCOL_MISMATCH
            statusCode != null -> StandardErrorCode.UNKNOWN_RESULT
            else -> StandardErrorCode.UNKNOWN_RESULT
        }
    }
}

internal fun parseResponsesErrorObject(bytes: ByteArray): JsonObject? = runCatching {
    OpenAiResponsesJson.objectFrom(bytes).objectValue("error")
}.getOrNull()

private data class ResponseContent(
    val text: List<String>,
    val refusal: List<String>,
)

private enum class CompletionKind { COMPLETED, INCOMPLETE, FAILED }

private fun parseOutput(root: JsonObject): ResponseContent {
    val text = mutableListOf<String>()
    val refusal = mutableListOf<String>()
    root.arrayValue("output").orEmpty().forEach { item ->
        val message = item as? JsonObject ?: return@forEach
        if (message.stringValue("type") != "message") return@forEach
        message.arrayValue("content").orEmpty().forEach { part ->
            val content = part as? JsonObject ?: return@forEach
            when (content.stringValue("type")) {
                "output_text" -> content.stringValue("text")?.let(text::add)
                "refusal" -> content.stringValue("refusal")?.let(refusal::add)
            }
        }
    }
    return ResponseContent(text, refusal)
}

private fun incompleteEvent(root: JsonObject, refusal: String): ProviderStreamEvent =
    when (root.objectValue("incomplete_details")?.stringValue("reason")) {
        "max_output_tokens" -> ProviderStreamEvent.Completed(ProviderFinishReason.LENGTH)
        "content_filter" -> refusalEvent(refusal.take(MAX_REFUSAL_CHARACTERS).ifBlank { null })
        else -> ProviderStreamEvent.Failed(StandardErrorCode.UNKNOWN_RESULT)
    }

private fun refusalEvent(message: String?): ProviderStreamEvent.Refused = ProviderStreamEvent.Refused(
    category = ProviderRefusalCategory.SAFETY,
    userFacingMessage = message?.takeIf(String::isNotBlank)?.let(SensitiveProviderText::from),
)

private fun contentEvent(value: String, structuredOutput: Boolean): ProviderStreamEvent =
    if (structuredOutput) ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from(value))
    else ProviderStreamEvent.TextDelta(SensitiveProviderText.from(value))

private fun parseResponsesUsage(root: JsonObject): ProviderUsage? {
    val input = root.nonNegativeLong("input_tokens")
    val output = root.nonNegativeLong("output_tokens")
    val total = root.nonNegativeLong("total_tokens")
    val inputDetails = root.objectValue("input_tokens_details")
    val outputDetails = root.objectValue("output_tokens_details")
    val cachedInput = inputDetails?.nonNegativeLong("cached_tokens")
    val cachedWrite = inputDetails?.nonNegativeLong("cache_write_tokens")
    val reasoning = outputDetails?.nonNegativeLong("reasoning_tokens")
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
        throw OpenAiResponsesProtocolException()
    }
}

private fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.arrayValue(name: String): JsonArray? = this[name] as? JsonArray
private fun JsonObject.stringValue(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.nonNegativeLong(name: String): Long? {
    val element: JsonElement = this[name] ?: return null
    val value = (element as? JsonPrimitive)?.longOrNull ?: throw OpenAiResponsesProtocolException()
    if (value < 0) throw OpenAiResponsesProtocolException()
    return value
}

private const val MAX_REFUSAL_CHARACTERS = 32_768
