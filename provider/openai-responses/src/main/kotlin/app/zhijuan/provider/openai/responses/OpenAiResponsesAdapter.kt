package app.zhijuan.provider.openai.responses

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.provider.common.CapabilityResult
import app.zhijuan.provider.common.ConnectionTestResult
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.ModelListResult
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderCallFailure
import app.zhijuan.provider.common.ProviderCancellationResult
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderEventDecision
import app.zhijuan.provider.common.ProviderEventGate
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderModelSummary
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderRemoteRequestId
import app.zhijuan.provider.common.RetryAfterParser
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.stream.SseStreamParser
import app.zhijuan.provider.transport.PrimarySecretHeader
import app.zhijuan.provider.transport.PrimarySecretScheme
import app.zhijuan.provider.transport.ProviderHttpMethod
import app.zhijuan.provider.transport.ProviderHttpOpenResult
import app.zhijuan.provider.transport.ProviderHttpRequestSpec
import app.zhijuan.provider.transport.ProviderHttpResponseLease
import app.zhijuan.provider.transport.ProviderTransportCancellationResult
import app.zhijuan.provider.transport.SecureProviderHttpTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okio.BufferedSource

class OpenAiResponsesAdapter(
    private val transport: SecureProviderHttpTransport,
    private val capabilityResolver: OpenAiResponsesCapabilityResolver =
        ConservativeOpenAiResponsesCapabilityResolver(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectionTestTimeouts: ProviderTimeoutPolicy = DEFAULT_CONNECTION_TIMEOUTS,
) : ProviderAdapter {
    override val protocol: ProviderProtocol = ProviderProtocol.OPENAI_RESPONSES
    override val adapterVersion: String = ADAPTER_VERSION

    private val auxiliaryRequestSequence = AtomicLong()

    override suspend fun testConnection(profile: ProviderConnectionProfile): ConnectionTestResult =
        when (val result = listModels(profile)) {
            is ModelListResult.Success -> ConnectionTestResult.Success(
                verifiedAt = result.fetchedAt,
                minimalGenerationVerified = false,
                usageObserved = false,
            )
            is ModelListResult.Failure -> ConnectionTestResult.Failure(result.failure)
        }

    override suspend fun listModels(profile: ProviderConnectionProfile): ModelListResult = withContext(Dispatchers.IO) {
        if (profile.protocol != protocol) {
            return@withContext ModelListResult.Failure(
                ProviderCallFailure(StandardErrorCode.PROTOCOL_MISMATCH),
            )
        }
        try {
            val specification = ProviderHttpRequestSpec(
                requestId = "responses-models-" + auxiliaryRequestSequence.incrementAndGet(),
                profile = profile,
                method = ProviderHttpMethod.GET,
                pathSegments = listOf("models"),
                publicHeaders = mapOf("Accept" to "application/json"),
                primarySecretHeader = primarySecretHeader(profile),
                maximumResponseBytes = MAXIMUM_MODEL_LIST_BYTES,
            )
            when (val opened = transport.open(specification, connectionTestTimeouts)) {
                is ProviderHttpOpenResult.Failed -> ModelListResult.Failure(opened.failure)
                ProviderHttpOpenResult.Cancelled -> ModelListResult.Failure(
                    ProviderCallFailure(StandardErrorCode.STREAM_INTERRUPTED),
                )
                ProviderHttpOpenResult.AlreadyActive -> ModelListResult.Failure(
                    ProviderCallFailure(StandardErrorCode.UNKNOWN_RESULT),
                )
                is ProviderHttpOpenResult.Opened -> readModelList(opened.response)
            }
        } catch (_: IllegalArgumentException) {
            ModelListResult.Failure(ProviderCallFailure(StandardErrorCode.PROTOCOL_MISMATCH))
        } catch (_: IllegalStateException) {
            ModelListResult.Failure(ProviderCallFailure(StandardErrorCode.PROTOCOL_MISMATCH))
        }
    }

    override suspend fun getCapabilities(
        profile: ProviderConnectionProfile,
        modelId: ProviderModelId,
    ): CapabilityResult {
        if (profile.protocol != protocol) {
            return CapabilityResult.Failure(ProviderCallFailure(StandardErrorCode.PROTOCOL_MISMATCH))
        }
        return try {
            CapabilityResult.Success(
                capabilityResolver.resolve(
                    profile,
                    modelId,
                    clock().coerceAtLeast(0),
                    adapterVersion,
                ),
            )
        } catch (_: IllegalArgumentException) {
            CapabilityResult.Failure(ProviderCallFailure(StandardErrorCode.PROTOCOL_MISMATCH))
        } catch (_: IllegalStateException) {
            CapabilityResult.Failure(ProviderCallFailure(StandardErrorCode.PROTOCOL_MISMATCH))
        }
    }

    override fun generate(
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
    ): Flow<ProviderStreamEvent> = flow {
        val gate = ProviderEventGate()
        try {
            if (profile.protocol != protocol) throw OpenAiResponsesProtocolException()
            val capabilities = capabilityResolver.resolve(
                profile,
                request.modelId,
                clock().coerceAtLeast(0),
                adapterVersion,
            )
            val body = OpenAiResponsesRequestEncoder.encode(profile, request, capabilities)
            val specification = ProviderHttpRequestSpec(
                requestId = request.requestId,
                profile = profile,
                method = ProviderHttpMethod.POST,
                pathSegments = listOf("responses"),
                publicHeaders = mapOf(
                    "Accept" to if (request.stream) "text/event-stream" else "application/json",
                ),
                primarySecretHeader = primarySecretHeader(profile),
                body = body,
                maximumResponseBytes = MAXIMUM_GENERATION_RESPONSE_BYTES,
            )
            when (val opened = transport.open(specification, request.timeouts)) {
                is ProviderHttpOpenResult.Failed -> emitAccepted(
                    gate,
                    ProviderStreamEvent.Failed(
                        code = opened.failure.code,
                        httpStatus = opened.failure.httpStatus,
                        retryAfterMillis = opened.failure.retryAfterMillis,
                        requestState = opened.failure.requestState,
                    ),
                )
                ProviderHttpOpenResult.Cancelled -> emitAccepted(
                    gate,
                    ProviderStreamEvent.Completed(app.zhijuan.provider.common.ProviderFinishReason.CANCELLED),
                )
                ProviderHttpOpenResult.AlreadyActive -> emitAccepted(
                    gate,
                    ProviderStreamEvent.Failed(StandardErrorCode.UNKNOWN_RESULT),
                )
                is ProviderHttpOpenResult.Opened -> handleGenerationResponse(
                    response = opened.response,
                    requestedStreaming = request.stream,
                    structuredOutput = request.structuredOutputSchema != null,
                    gate = gate,
                )
            }
        } catch (cancelled: CancellationException) {
            transport.cancel(request.requestId)
            throw cancelled
        } catch (_: OpenAiResponsesUnsupportedFieldsException) {
            emitAccepted(gate, ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH))
        } catch (_: OpenAiResponsesProtocolException) {
            emitAccepted(gate, ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH))
        } catch (_: IllegalArgumentException) {
            emitAccepted(gate, ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH))
        } catch (_: IllegalStateException) {
            emitAccepted(gate, ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH))
        } catch (_: IOException) {
            emitAccepted(
                gate,
                ProviderStreamEvent.Failed(
                    StandardErrorCode.STREAM_INTERRUPTED,
                    requestState = FailureRequestState.RESPONSE_STARTED,
                ),
            )
        } finally {
            if (currentCoroutineContext().isActive) {
                gate.terminalForUnexpectedEnd()?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(
        profile: ProviderConnectionProfile,
        requestId: String,
    ): ProviderCancellationResult {
        if (profile.protocol != protocol) return ProviderCancellationResult.NOT_SUPPORTED
        return when (transport.cancel(requestId)) {
            ProviderTransportCancellationResult.CANCELLATION_REQUESTED,
            ProviderTransportCancellationResult.ALREADY_REQUESTED,
            -> ProviderCancellationResult.CANCELLED_LOCALLY
            ProviderTransportCancellationResult.NOT_ACTIVE -> ProviderCancellationResult.ALREADY_TERMINAL
        }
    }

    private suspend fun FlowCollector<ProviderStreamEvent>.handleGenerationResponse(
        response: ProviderHttpResponseLease,
        requestedStreaming: Boolean,
        structuredOutput: Boolean,
        gate: ProviderEventGate,
    ) {
        val retryAfter = retryAfterMillis(response)
        if (response.statusCode !in 200..299) {
            val bytes = response.withBodySourceSuspending { it.readByteArray() }
            val error = try {
                parseResponsesErrorObject(bytes)
            } finally {
                bytes.fill(0)
            }
            emitAccepted(
                gate,
                ProviderStreamEvent.Failed(
                    code = OpenAiResponsesErrorMapper.map(response.statusCode, error),
                    httpStatus = response.statusCode,
                    retryAfterMillis = retryAfter,
                    requestState = FailureRequestState.PROVIDER_REJECTED,
                ),
            )
            return
        }

        emitAccepted(gate, ProviderStreamEvent.Started(remoteRequestId(response)))
        val contentType = response.withHeaderValue("Content-Type") { it?.lowercase() }
        val responseIsJson = contentType?.contains("application/json") == true
        val responseIsSse = contentType?.contains("text/event-stream") == true
        when {
            requestedStreaming && !responseIsJson && (responseIsSse || contentType == null) -> {
                response.withBodySourceSuspending { consumeSse(it, structuredOutput, gate) }
            }
            (!requestedStreaming && !responseIsSse && (responseIsJson || contentType == null)) ||
                (requestedStreaming && responseIsJson) -> {
                val bytes = response.withBodySourceSuspending { it.readByteArray() }
                val root = try {
                    OpenAiResponsesJson.objectFrom(bytes)
                } finally {
                    bytes.fill(0)
                }
                OpenAiResponsesNonStreamingMapper.map(root, structuredOutput).forEach {
                    emitAccepted(gate, it)
                }
            }
            else -> {
                response.close()
                emitAccepted(gate, ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH))
            }
        }
    }

    private suspend fun FlowCollector<ProviderStreamEvent>.consumeSse(
        source: BufferedSource,
        structuredOutput: Boolean,
        gate: ProviderEventGate,
    ) {
        val parser = SseStreamParser()
        val mapper = OpenAiResponsesStreamMapper(structuredOutput)
        val buffer = ByteArray(STREAM_READ_BUFFER_BYTES)
        try {
            while (!mapper.isTerminal()) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val chunk = buffer.copyOf(read)
                val items = try {
                    parser.feed(chunk)
                } finally {
                    chunk.fill(0)
                }
                items.forEach { item ->
                    mapper.accept(item).forEach { emitAccepted(gate, it) }
                }
            }
            if (!mapper.isTerminal()) {
                parser.finish().forEach { item ->
                    mapper.accept(item).forEach { emitAccepted(gate, it) }
                }
                mapper.finishAtEof().forEach { emitAccepted(gate, it) }
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun readModelList(response: ProviderHttpResponseLease): ModelListResult {
        val retryAfter = retryAfterMillis(response)
        return try {
            val bytes = response.withBodySource { it.readByteArray() }
            try {
                if (response.statusCode !in 200..299) {
                    ModelListResult.Failure(
                        ProviderCallFailure(
                            code = OpenAiResponsesErrorMapper.map(
                                response.statusCode,
                                parseResponsesErrorObject(bytes),
                            ),
                            httpStatus = response.statusCode,
                            retryAfterMillis = retryAfter,
                            requestState = FailureRequestState.PROVIDER_REJECTED,
                        ),
                    )
                } else {
                    val models = OpenAiResponsesModelListMapper.map(OpenAiResponsesJson.objectFrom(bytes))
                        .mapNotNull { runCatching { ProviderModelSummary(ProviderModelId.from(it)) }.getOrNull() }
                    ModelListResult.Success(models, clock().coerceAtLeast(0))
                }
            } finally {
                bytes.fill(0)
            }
        } catch (_: IOException) {
            ModelListResult.Failure(ProviderCallFailure(StandardErrorCode.STREAM_INTERRUPTED))
        } catch (_: IllegalArgumentException) {
            ModelListResult.Failure(ProviderCallFailure(StandardErrorCode.PROTOCOL_MISMATCH))
        }
    }

    private fun primarySecretHeader(profile: ProviderConnectionProfile): PrimarySecretHeader? =
        profile.primarySecretRefId?.let { PrimarySecretHeader("Authorization", PrimarySecretScheme.BEARER) }

    private fun remoteRequestId(response: ProviderHttpResponseLease): ProviderRemoteRequestId? {
        for (name in REMOTE_REQUEST_ID_HEADERS) {
            val value = response.withHeaderValue(name) { it } ?: continue
            runCatching { ProviderRemoteRequestId.from(value) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun retryAfterMillis(response: ProviderHttpResponseLease): Long? =
        response.withHeaderValue("Retry-After") { raw ->
            RetryAfterParser.parse(raw, clock().coerceAtLeast(0))
        }

    private suspend fun FlowCollector<ProviderStreamEvent>.emitAccepted(
        gate: ProviderEventGate,
        event: ProviderStreamEvent,
    ) {
        val decision = gate.accept(event)
        if (decision is ProviderEventDecision.Emit) emit(decision.event)
    }

    private companion object {
        const val ADAPTER_VERSION = "openai-responses-1"
        const val MAXIMUM_MODEL_LIST_BYTES = 2L * 1024 * 1024
        const val MAXIMUM_GENERATION_RESPONSE_BYTES = 64L * 1024 * 1024
        const val STREAM_READ_BUFFER_BYTES = 8 * 1024
        val REMOTE_REQUEST_ID_HEADERS = listOf("X-Request-Id", "OpenAI-Request-Id", "Request-Id")
        val DEFAULT_CONNECTION_TIMEOUTS = ProviderTimeoutPolicy(
            connectMillis = 5_000,
            firstByteMillis = 15_000,
            streamIdleMillis = 15_000,
            totalStageMillis = 60_000,
        )
    }
}
