package app.zhijuan.provider.gemini

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
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderModelId
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
import app.zhijuan.provider.transport.PublicQueryParameter
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

class GeminiGenerateContentAdapter(
    private val transport: SecureProviderHttpTransport,
    private val capabilityResolver: GeminiCapabilityResolver = ConservativeGeminiCapabilityResolver(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectionTestTimeouts: ProviderTimeoutPolicy = DEFAULT_CONNECTION_TIMEOUTS,
) : ProviderAdapter {
    override val protocol = ProviderProtocol.GEMINI_GENERATE_CONTENT
    override val adapterVersion = ADAPTER_VERSION
    private val auxiliarySequence = AtomicLong()

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
            return@withContext ModelListResult.Failure(ProviderCallFailure(StandardErrorCode.PROTOCOL_MISMATCH))
        }
        try {
            val spec = ProviderHttpRequestSpec(
                requestId = "gemini-models-" + auxiliarySequence.incrementAndGet(),
                profile = profile,
                method = ProviderHttpMethod.GET,
                pathSegments = listOf("models"),
                queryParameters = listOf(PublicQueryParameter("pageSize", MAXIMUM_MODEL_PAGE_SIZE.toString())),
                publicHeaders = apiHeaders("application/json"),
                primarySecretHeader = primarySecretHeader(profile),
                maximumResponseBytes = MAXIMUM_MODEL_LIST_BYTES,
            )
            when (val opened = transport.open(spec, connectionTestTimeouts)) {
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
                capabilityResolver.resolve(profile, modelId, clock().coerceAtLeast(0), adapterVersion),
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
            if (profile.protocol != protocol) throw GeminiProtocolException()
            val capabilities = capabilityResolver.resolve(
                profile,
                request.modelId,
                clock().coerceAtLeast(0),
                adapterVersion,
            )
            val body = GeminiRequestEncoder.encode(profile, request, capabilities)
            val spec = ProviderHttpRequestSpec(
                requestId = request.requestId,
                profile = profile,
                method = ProviderHttpMethod.POST,
                pathSegments = GeminiRequestEncoder.generationPath(request.modelId, request.stream),
                queryParameters = if (request.stream) listOf(PublicQueryParameter("alt", "sse")) else emptyList(),
                publicHeaders = apiHeaders(
                    if (request.stream) "text/event-stream" else "application/json",
                ),
                primarySecretHeader = primarySecretHeader(profile),
                body = body,
                maximumResponseBytes = MAXIMUM_GENERATION_RESPONSE_BYTES,
            )
            when (val opened = transport.open(spec, request.timeouts)) {
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
                    ProviderStreamEvent.Completed(ProviderFinishReason.CANCELLED),
                )
                ProviderHttpOpenResult.AlreadyActive -> emitAccepted(
                    gate,
                    ProviderStreamEvent.Failed(StandardErrorCode.UNKNOWN_RESULT),
                )
                is ProviderHttpOpenResult.Opened -> handleResponse(
                    opened.response,
                    request.stream,
                    request.structuredOutputSchema != null,
                    gate,
                )
            }
        } catch (cancelled: CancellationException) {
            transport.cancel(request.requestId)
            throw cancelled
        } catch (_: GeminiUnsupportedFieldsException) {
            emitAccepted(gate, ProviderStreamEvent.Failed(StandardErrorCode.PROTOCOL_MISMATCH))
        } catch (_: GeminiProtocolException) {
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

    private suspend fun FlowCollector<ProviderStreamEvent>.handleResponse(
        response: ProviderHttpResponseLease,
        requestedStreaming: Boolean,
        structuredOutput: Boolean,
        gate: ProviderEventGate,
    ) {
        val retryAfter = retryAfterMillis(response)
        if (response.statusCode !in 200..299) {
            val bytes = response.withBodySourceSuspending { it.readByteArray() }
            val error = try { parseGeminiErrorObject(bytes) } finally { bytes.fill(0) }
            emitAccepted(
                gate,
                ProviderStreamEvent.Failed(
                    code = GeminiErrorMapper.map(response.statusCode, error, retryAfter),
                    httpStatus = response.statusCode,
                    retryAfterMillis = retryAfter,
                    requestState = FailureRequestState.PROVIDER_REJECTED,
                ),
            )
            return
        }

        emitAccepted(gate, ProviderStreamEvent.Started(remoteRequestId(response)))
        val contentType = response.withHeaderValue("Content-Type") { it?.lowercase() }
        val isJson = contentType?.contains("application/json") == true
        val isSse = contentType?.contains("text/event-stream") == true
        when {
            requestedStreaming && !isJson && (isSse || contentType == null) -> {
                response.withBodySourceSuspending { consumeSse(it, structuredOutput, gate) }
            }
            (!requestedStreaming && !isSse && (isJson || contentType == null)) ||
                (requestedStreaming && isJson) -> {
                val bytes = response.withBodySourceSuspending { it.readByteArray() }
                val root = try { GeminiJson.objectFrom(bytes) } finally { bytes.fill(0) }
                GeminiNonStreamingMapper.map(root, structuredOutput).forEach {
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
        val mapper = GeminiStreamMapper(structuredOutput)
        val buffer = ByteArray(STREAM_READ_BUFFER_BYTES)
        try {
            while (!mapper.isTerminal()) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val chunk = buffer.copyOf(read)
                val items = try { parser.feed(chunk) } finally { chunk.fill(0) }
                items.forEach { item -> mapper.accept(item).forEach { emitAccepted(gate, it) } }
            }
            if (!mapper.isTerminal()) {
                parser.finish().forEach { item -> mapper.accept(item).forEach { emitAccepted(gate, it) } }
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
                            code = GeminiErrorMapper.map(
                                response.statusCode,
                                parseGeminiErrorObject(bytes),
                                retryAfter,
                            ),
                            httpStatus = response.statusCode,
                            retryAfterMillis = retryAfter,
                            requestState = FailureRequestState.PROVIDER_REJECTED,
                        ),
                    )
                } else {
                    ModelListResult.Success(
                        models = GeminiModelListMapper.map(GeminiJson.objectFrom(bytes)),
                        fetchedAt = clock().coerceAtLeast(0),
                    )
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

    private fun apiHeaders(accept: String) = mapOf("Accept" to accept)

    private fun primarySecretHeader(profile: ProviderConnectionProfile): PrimarySecretHeader? =
        profile.primarySecretRefId?.let { PrimarySecretHeader("x-goog-api-key", PrimarySecretScheme.RAW) }

    private fun remoteRequestId(response: ProviderHttpResponseLease): ProviderRemoteRequestId? {
        val value = response.withHeaderValue("x-request-id") { it }
            ?: response.withHeaderValue("x-goog-request-id") { it }
        return value?.let { runCatching { ProviderRemoteRequestId.from(it) }.getOrNull() }
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
        const val ADAPTER_VERSION = "gemini-generate-content-1"
        const val MAXIMUM_MODEL_PAGE_SIZE = 1_000
        const val MAXIMUM_MODEL_LIST_BYTES = 2L * 1024 * 1024
        const val MAXIMUM_GENERATION_RESPONSE_BYTES = 64L * 1024 * 1024
        const val STREAM_READ_BUFFER_BYTES = 8 * 1024
        val DEFAULT_CONNECTION_TIMEOUTS = ProviderTimeoutPolicy(5_000, 15_000, 15_000, 60_000)
    }
}
