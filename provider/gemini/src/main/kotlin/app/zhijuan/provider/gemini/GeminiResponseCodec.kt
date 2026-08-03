package app.zhijuan.provider.gemini

import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderModelSummary
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal object GeminiJson {
    private val parser = Json { ignoreUnknownKeys = true; isLenient = false }

    fun objectFrom(value: String): JsonObject =
        parser.parseToJsonElement(value) as? JsonObject ?: throw GeminiProtocolException()

    fun objectFrom(bytes: ByteArray): JsonObject = objectFrom(bytes.decodeToString())
}

internal class GeminiProtocolException : IllegalArgumentException("Gemini payload is invalid.")

internal class GeminiStreamMapper(
    private val structuredOutput: Boolean,
) {
    private var terminal = false
    private var pendingFinishReason: String? = null
    private var sawFunctionCall = false
    private var sawResponseEvidence = false

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
        val reason = pendingFinishReason
        return if (reason != null) {
            listOf(geminiTerminalEvent(reason, sawFunctionCall))
        } else {
            listOf(
                ProviderStreamEvent.Failed(
                    if (sawResponseEvidence) StandardErrorCode.STREAM_INTERRUPTED
                    else StandardErrorCode.PROTOCOL_MISMATCH,
                ),
            )
        }
    }

    fun isTerminal() = terminal

    private fun acceptEvent(item: SseItem.Event): List<ProviderStreamEvent> {
        if (item.value.event != "message") return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        val root = runCatching { GeminiJson.objectFrom(item.value.data) }
            .getOrElse { return fail(StandardErrorCode.PROTOCOL_MISMATCH) }
        root.objectValue("error")?.let { return fail(GeminiErrorMapper.map(null, it)) }
        val parsed = runCatching { parseGeminiResponse(root, structuredOutput) }
            .getOrElse { return fail(StandardErrorCode.PROTOCOL_MISMATCH) }
        if (!parsed.hasEvidence) return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        sawResponseEvidence = true

        parsed.promptBlockReason?.let { blockReason ->
            terminal = true
            return buildList {
                parsed.usage?.let { add(ProviderStreamEvent.UsageUpdate(it)) }
                add(promptBlockEvent(blockReason))
            }
        }
        if (pendingFinishReason != null && parsed.contentEvents.isNotEmpty()) {
            return fail(StandardErrorCode.PROTOCOL_MISMATCH)
        }
        parsed.finishReason?.let { incoming ->
            val previous = pendingFinishReason
            if (previous != null && previous != incoming) {
                return fail(StandardErrorCode.PROTOCOL_MISMATCH)
            }
            pendingFinishReason = incoming
        }
        sawFunctionCall = sawFunctionCall || parsed.hasFunctionCall
        return buildList {
            addAll(parsed.contentEvents)
            parsed.usage?.let { add(ProviderStreamEvent.UsageUpdate(it)) }
        }
    }

    private fun fail(code: StandardErrorCode): List<ProviderStreamEvent> {
        terminal = true
        return listOf(ProviderStreamEvent.Failed(code))
    }
}

internal object GeminiNonStreamingMapper {
    fun map(root: JsonObject, structuredOutput: Boolean): List<ProviderStreamEvent> {
        root.objectValue("error")?.let {
            return listOf(ProviderStreamEvent.Failed(GeminiErrorMapper.map(null, it)))
        }
        val parsed = try {
            parseGeminiResponse(root, structuredOutput)
        } catch (_: IllegalArgumentException) {
            return listOf(ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH))
        }
        parsed.promptBlockReason?.let { blockReason ->
            return buildList {
                parsed.usage?.let { add(ProviderStreamEvent.UsageUpdate(it)) }
                add(promptBlockEvent(blockReason))
            }
        }
        val reason = parsed.finishReason
            ?: return listOf(ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH))
        return buildList {
            addAll(parsed.contentEvents)
            parsed.usage?.let { add(ProviderStreamEvent.UsageUpdate(it)) }
            add(geminiTerminalEvent(reason, parsed.hasFunctionCall))
        }
    }
}

internal object GeminiModelListMapper {
    fun map(root: JsonObject): List<ProviderModelSummary> {
        val models = root.arrayValue("models") ?: throw GeminiProtocolException()
        return models.mapNotNull { element ->
            val model = element as? JsonObject ?: throw GeminiProtocolException()
            val methods = model.stringArray("supportedGenerationMethods")
                ?: model.stringArray("supportedActions")
                ?: emptyList()
            if (methods.none { it.equals("generateContent", ignoreCase = true) }) return@mapNotNull null
            val name = model.stringValue("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            runCatching {
                ProviderModelSummary(
                    id = ProviderModelId.from(name),
                    contextLimitHint = model.positiveIntHint("inputTokenLimit", minimum = 1_024),
                    maxOutputTokensHint = model.positiveIntHint("outputTokenLimit", minimum = 1),
                )
            }.getOrNull()
        }.distinctBy(ProviderModelSummary::id)
    }
}

internal object GeminiErrorMapper {
    fun map(
        statusCode: Int?,
        error: JsonObject?,
        retryAfterMillis: Long? = null,
    ): StandardErrorCode {
        val status = error?.stringValue("status")?.uppercase().orEmpty()
        return when {
            status in setOf("UNAUTHENTICATED", "PERMISSION_DENIED") -> StandardErrorCode.AUTH_FAILED
            status == "NOT_FOUND" -> StandardErrorCode.MODEL_NOT_FOUND
            status == "RESOURCE_EXHAUSTED" -> if (retryAfterMillis != null) {
                StandardErrorCode.RATE_LIMITED
            } else {
                StandardErrorCode.QUOTA_EXHAUSTED
            }
            status == "FAILED_PRECONDITION" -> StandardErrorCode.QUOTA_EXHAUSTED
            status in setOf("INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED") -> {
                StandardErrorCode.SERVER_OVERLOADED
            }
            status == "CANCELLED" -> StandardErrorCode.STREAM_INTERRUPTED
            status in setOf("INVALID_ARGUMENT", "OUT_OF_RANGE", "UNIMPLEMENTED") -> {
                StandardErrorCode.PROTOCOL_MISMATCH
            }
            statusCode == 401 || statusCode == 403 -> StandardErrorCode.AUTH_FAILED
            statusCode == 404 -> StandardErrorCode.MODEL_NOT_FOUND
            statusCode == 413 -> StandardErrorCode.CONTEXT_TOO_LARGE
            statusCode == 429 -> if (retryAfterMillis != null) {
                StandardErrorCode.RATE_LIMITED
            } else {
                StandardErrorCode.QUOTA_EXHAUSTED
            }
            statusCode in setOf(500, 502, 503, 504) -> StandardErrorCode.SERVER_OVERLOADED
            statusCode in setOf(400, 405, 415, 422) -> StandardErrorCode.PROTOCOL_MISMATCH
            statusCode != null -> StandardErrorCode.UNKNOWN_RESULT
            else -> StandardErrorCode.PROTOCOL_MISMATCH
        }
    }
}

internal fun parseGeminiErrorObject(bytes: ByteArray): JsonObject? = runCatching {
    GeminiJson.objectFrom(bytes).objectValue("error")
}.getOrNull()

private data class ParsedGeminiResponse(
    val contentEvents: List<ProviderStreamEvent>,
    val usage: ProviderUsage?,
    val promptBlockReason: String?,
    val finishReason: String?,
    val hasFunctionCall: Boolean,
    val hasEvidence: Boolean,
)

private fun parseGeminiResponse(
    root: JsonObject,
    structuredOutput: Boolean,
): ParsedGeminiResponse {
    val promptFeedback = root.objectValueStrict("promptFeedback")
    val blockReason = promptFeedback?.optionalStringStrict("blockReason")
    val usage = root.objectValueStrict("usageMetadata")?.let(::parseGeminiUsage)
    val candidates = root.arrayValueStrict("candidates")
    if (blockReason != null && !candidates.isNullOrEmpty()) throw GeminiProtocolException()
    val candidate = candidates?.selectCandidateZero()
    val parsedCandidate = candidate?.let(::parseCandidate)
    val events = parsedCandidate?.textFragments.orEmpty().map {
        if (structuredOutput) ProviderStreamEvent.StructuredDelta(SensitiveProviderText.from(it))
        else ProviderStreamEvent.TextDelta(SensitiveProviderText.from(it))
    }
    return ParsedGeminiResponse(
        contentEvents = events,
        usage = usage,
        promptBlockReason = blockReason,
        finishReason = parsedCandidate?.finishReason,
        hasFunctionCall = parsedCandidate?.hasFunctionCall == true,
        hasEvidence = promptFeedback != null || usage != null || candidates != null,
    )
}

private data class ParsedGeminiCandidate(
    val textFragments: List<String>,
    val finishReason: String?,
    val hasFunctionCall: Boolean,
)

private fun parseCandidate(candidate: JsonObject): ParsedGeminiCandidate {
    val finishReason = candidate.optionalStringStrict("finishReason")
    val content = candidate.objectValueStrict("content")
    content?.optionalStringStrict("role")?.let {
        if (it != "model") throw GeminiProtocolException()
    }
    var hasFunctionCall = false
    val fragments = content?.arrayValueStrict("parts").orEmpty().mapNotNull { element ->
        val part = element as? JsonObject ?: throw GeminiProtocolException()
        if (part["functionCall"] != null) {
            if (part["functionCall"] !is JsonObject) throw GeminiProtocolException()
            hasFunctionCall = true
        }
        val thought = part.optionalBooleanStrict("thought") ?: false
        val text = part.optionalStringStrict("text")
        text?.takeIf { !thought && it.isNotEmpty() }
    }
    return ParsedGeminiCandidate(fragments, finishReason, hasFunctionCall)
}

private fun parseGeminiUsage(root: JsonObject): ProviderUsage? {
    val input = root.nonNegativeLong("promptTokenCount")
    val output = root.nonNegativeLong("candidatesTokenCount")
    val cachedInput = root.nonNegativeLong("cachedContentTokenCount")
    val reasoning = root.nonNegativeLong("thoughtsTokenCount")
    val total = root.nonNegativeLong("totalTokenCount")
    if (listOf(input, output, cachedInput, reasoning, total).all { it == null }) return null
    return try {
        ProviderUsage(
            inputTokens = input,
            outputTokens = output,
            cachedInputTokens = cachedInput,
            cachedWriteTokens = null,
            reasoningTokens = reasoning,
            totalTokens = total,
            quality = ProviderUsageQuality.PROVIDER_REPORTED,
        )
    } catch (_: IllegalArgumentException) {
        throw GeminiProtocolException()
    }
}

private fun promptBlockEvent(reason: String): ProviderStreamEvent = when (reason) {
    "SAFETY", "IMAGE_SAFETY" -> ProviderStreamEvent.Refused(ProviderRefusalCategory.SAFETY)
    "BLOCKLIST", "PROHIBITED_CONTENT" -> ProviderStreamEvent.Refused(ProviderRefusalCategory.POLICY)
    "OTHER" -> ProviderStreamEvent.Refused(ProviderRefusalCategory.UNKNOWN)
    "BLOCK_REASON_UNSPECIFIED" -> ProviderStreamEvent.Failed(StandardErrorCode.UNKNOWN_RESULT)
    else -> ProviderStreamEvent.Failed(StandardErrorCode.UNKNOWN_RESULT)
}

private fun geminiTerminalEvent(reason: String, hasFunctionCall: Boolean): ProviderStreamEvent = when (reason) {
    "STOP" -> if (hasFunctionCall) {
        ProviderStreamEvent.Completed(ProviderFinishReason.TOOL_CALL)
    } else {
        ProviderStreamEvent.Completed(ProviderFinishReason.STOP)
    }
    "MAX_TOKENS" -> ProviderStreamEvent.Completed(ProviderFinishReason.LENGTH)
    "SAFETY", "IMAGE_SAFETY", "SPII" -> ProviderStreamEvent.Refused(ProviderRefusalCategory.SAFETY)
    "RECITATION", "IMAGE_RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT",
    "IMAGE_PROHIBITED_CONTENT", "ESCALATION",
    -> ProviderStreamEvent.Refused(ProviderRefusalCategory.POLICY)
    "LANGUAGE", "NO_IMAGE", "IMAGE_OTHER" -> {
        ProviderStreamEvent.Refused(ProviderRefusalCategory.UNSUPPORTED_REQUEST)
    }
    "MALFORMED_FUNCTION_CALL", "MISSING_THOUGHT_SIGNATURE", "MALFORMED_RESPONSE" -> {
        ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH)
    }
    "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "OTHER", "FINISH_REASON_UNSPECIFIED" -> {
        ProviderStreamEvent.Failed(StandardErrorCode.UNKNOWN_RESULT)
    }
    else -> ProviderStreamEvent.Failed(StandardErrorCode.UNKNOWN_RESULT)
}

private fun JsonObject.objectValue(name: String) = this[name] as? JsonObject

private fun JsonObject.objectValueStrict(name: String): JsonObject? {
    val element = this[name] ?: return null
    return element as? JsonObject ?: throw GeminiProtocolException()
}

private fun JsonObject.arrayValue(name: String) = this[name] as? JsonArray

private fun JsonObject.arrayValueStrict(name: String): JsonArray? {
    val element = this[name] ?: return null
    return element as? JsonArray ?: throw GeminiProtocolException()
}

private fun JsonObject.stringValue(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.optionalStringStrict(name: String): String? {
    val element = this[name] ?: return null
    return (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: throw GeminiProtocolException()
}

private fun JsonObject.optionalBooleanStrict(name: String): Boolean? {
    val element = this[name] ?: return null
    return (element as? JsonPrimitive)?.booleanOrNull ?: throw GeminiProtocolException()
}

private fun JsonObject.nonNegativeLong(name: String): Long? {
    val element: JsonElement = this[name] ?: return null
    val value = (element as? JsonPrimitive)?.longOrNull ?: throw GeminiProtocolException()
    if (value < 0) throw GeminiProtocolException()
    return value
}

private fun JsonObject.stringArray(name: String): List<String>? {
    val array = arrayValueStrict(name) ?: return null
    return array.map {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw GeminiProtocolException()
    }
}

private fun JsonObject.positiveIntHint(name: String, minimum: Int): Int? {
    val raw = nonNegativeLong(name) ?: return null
    return raw.takeIf { it in minimum.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
}

private fun JsonArray.selectCandidateZero(): JsonObject? {
    if (isEmpty()) return null
    val objects = map { it as? JsonObject ?: throw GeminiProtocolException() }
    objects.firstOrNull { it.nonNegativeLong("index") == 0L }?.let { return it }
    return objects.singleOrNull()?.takeIf { it["index"] == null }
        ?: throw GeminiProtocolException()
}
