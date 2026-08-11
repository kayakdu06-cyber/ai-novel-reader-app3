package app.zhijuan.provider.transport

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.network.EndpointPolicy
import app.zhijuan.core.network.EndpointRejectedException
import app.zhijuan.core.network.NetworkFailureClassifier
import app.zhijuan.core.network.RedirectRejectedException
import app.zhijuan.core.network.SecureOkHttpClientFactory
import app.zhijuan.core.network.SecureRedirectExecutor
import app.zhijuan.provider.common.ProviderCallFailure
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import java.io.IOException
import java.net.ProtocolException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

class ProviderHttpResponseLease internal constructor(
    response: Response,
    private val maximumResponseBytes: Long,
    private val streamIdleMillis: Long,
    private val onReadFailure: (IOException) -> Unit,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val response = response
    private val bodySource: BufferedSource by lazy(::createBoundedSource)

    @Volatile
    private var closed = false

    @Volatile
    private var bodyBorrowed = false

    val statusCode: Int
        get() = response.code

    val hasBody: Boolean
        get() = response.body.contentLength() != 0L

    fun <T> withHeaderValue(name: String, block: (String?) -> T): T {
        check(!closed) { "Provider HTTP response is closed." }
        val normalized = name.lowercase()
        require(normalized in SAFE_RESPONSE_HEADERS) { "Response header is not exposed by the transport." }
        return block(response.header(name))
    }

    fun <T> withBodySource(block: (BufferedSource) -> T): T {
        borrowBody()
        return try {
            block(bodySource)
        } catch (error: IOException) {
            runCatching { onReadFailure(error) }
            throw error
        } finally {
            close()
        }
    }

    suspend fun <T> withBodySourceSuspending(block: suspend (BufferedSource) -> T): T {
        borrowBody()
        return try {
            block(bodySource)
        } catch (error: IOException) {
            runCatching { onReadFailure(error) }
            throw error
        } finally {
            close()
        }
    }

    override fun close() {
        if (closed) return
        try {
            synchronized(this) {
                if (closed) return
                closed = true
                response.close()
            }
        } finally {
            runCatching(onClose)
        }
    }

    override fun toString(): String =
        "ProviderHttpResponseLease(statusCode=$statusCode, hasBody=$hasBody)"

    private fun createBoundedSource(): BufferedSource {
        val source = response.body.source()
        source.timeout().timeout(streamIdleMillis, TimeUnit.MILLISECONDS)
        return object : ForwardingSource(source) {
            private var bytesRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val remainingIncludingProbe = maximumResponseBytes - bytesRead + 1
                val allowed = minOf(byteCount, remainingIncludingProbe)
                val read = super.read(sink, allowed)
                if (read > 0) {
                    bytesRead += read
                    if (bytesRead > maximumResponseBytes) {
                        throw ProtocolException("Provider response exceeded the configured byte limit.")
                    }
                }
                return read
            }
        }.buffer()
    }

    private fun borrowBody() {
        synchronized(this) {
            check(!closed) { "Provider HTTP response is closed." }
            check(!bodyBorrowed) { "Provider HTTP response body can only be consumed once." }
            bodyBorrowed = true
        }
    }

    private companion object {
        val SAFE_RESPONSE_HEADERS = setOf(
            "content-length",
            "content-type",
            "retry-after",
            "request-id",
            "x-request-id",
            "x-goog-request-id",
            "openai-request-id",
            "anthropic-request-id",
            "x-ratelimit-limit-requests",
            "x-ratelimit-remaining-requests",
            "x-ratelimit-reset-requests",
        )
    }
}

class SecureProviderHttpTransport(
    private val secretSource: ProviderSecretMaterialSource,
    private val diagnostics: ProviderTransportDiagnosticSink = NoOpProviderTransportDiagnosticSink,
    private val baseClient: OkHttpClient = SecureOkHttpClientFactory.create(),
    private val endpointPolicy: EndpointPolicy = EndpointPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val active = ConcurrentHashMap<String, ActiveExchange>()

    init {
        require(!baseClient.followRedirects) { "Automatic redirects must be disabled." }
        require(!baseClient.followSslRedirects) { "Automatic SSL redirects must be disabled." }
        require(!baseClient.retryOnConnectionFailure) { "Automatic connection retries must be disabled." }
    }

    fun open(
        specification: ProviderHttpRequestSpec,
        timeouts: ProviderTimeoutPolicy,
    ): ProviderHttpOpenResult {
        val startedAt = clock().coerceAtLeast(0)
        val exchange = ActiveExchange(specification, startedAt)
        if (active.putIfAbsent(specification.requestId, exchange) != null) {
            specification.close()
            return ProviderHttpOpenResult.AlreadyActive
        }
        record(exchange.diagnostic(ProviderTransportDiagnosticCode.REQUEST_STARTED, now = startedAt))

        var opened = false
        return try {
            val built = buildRequest(specification, startedAt)
            val client = baseClient.newBuilder()
                .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeouts.firstByteMillis, TimeUnit.MILLISECONDS)
                .callTimeout(timeouts.totalStageMillis, TimeUnit.MILLISECONDS)
                .build()
            val executor = SecureRedirectExecutor(client, endpointPolicy)
            val response = executor.execute(
                request = built.request,
                allowExplicitLocalCleartext = specification.profile.explicitLocalCleartext,
                onCallCreated = { call ->
                    val elapsed = (clock() - startedAt).coerceAtLeast(0)
                    val remaining = (timeouts.totalStageMillis - elapsed).coerceAtLeast(1)
                    call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
                    exchange.attach(call)
                },
            )
            if (exchange.isCancellationRequested()) {
                response.close()
                ProviderHttpOpenResult.Cancelled
            } else if (
                response.body.contentLength() > specification.maximumResponseBytes &&
                response.body.contentLength() >= 0
            ) {
                response.close()
                throw ProtocolException("Provider response declared a body larger than the configured limit.")
            } else {
                val sanitized = sanitizeResponse(response, built.secretHeaderNames)
                val lease = ProviderHttpResponseLease(
                    response = sanitized,
                    maximumResponseBytes = specification.maximumResponseBytes,
                    streamIdleMillis = timeouts.streamIdleMillis,
                    onReadFailure = { error ->
                        if (!exchange.isCancellationRequested()) {
                            recordFailure(exchange, NetworkFailureClassifier.classify(error), error, null)
                        }
                    },
                    onClose = { active.remove(specification.requestId, exchange) },
                )
                exchange.attach(lease::close)
                if (exchange.isCancellationRequested()) {
                    lease.close()
                    ProviderHttpOpenResult.Cancelled
                } else {
                    opened = true
                    record(
                        exchange.diagnostic(
                            code = ProviderTransportDiagnosticCode.RESPONSE_OPENED,
                            now = clock(),
                            httpStatus = lease.statusCode,
                        ),
                    )
                    ProviderHttpOpenResult.Opened(lease)
                }
            }
        } catch (error: Exception) {
            if (exchange.isCancellationRequested()) {
                ProviderHttpOpenResult.Cancelled
            } else {
                val code = classify(error)
                recordFailure(exchange, code, error, null)
                ProviderHttpOpenResult.Failed(
                    ProviderCallFailure(
                        code = code,
                        requestState = requestStateFor(error, code),
                    ),
                )
            }
        } finally {
            specification.close()
            if (!opened) active.remove(specification.requestId, exchange)
        }
    }

    fun cancel(requestId: String): ProviderTransportCancellationResult {
        val exchange = active[requestId] ?: return ProviderTransportCancellationResult.NOT_ACTIVE
        if (!exchange.cancel()) return ProviderTransportCancellationResult.ALREADY_REQUESTED
        record(exchange.diagnostic(ProviderTransportDiagnosticCode.REQUEST_CANCELLED, now = clock()))
        return ProviderTransportCancellationResult.CANCELLATION_REQUESTED
    }

    private fun buildRequest(
        specification: ProviderHttpRequestSpec,
        now: Long,
    ): BuiltRequest {
        val endpoint = specification.profile.withBaseUrl { baseUrl ->
            endpointPolicy.validateBaseUrl(
                baseUrl,
                allowExplicitLocalCleartext = specification.profile.explicitLocalCleartext,
            )
        }
        val urlBuilder = endpoint.url.newBuilder()
        specification.withPathSegments { segments -> segments.forEach(urlBuilder::addPathSegment) }
        specification.withQueryParameters { parameters ->
            parameters.forEach { parameter ->
                parameter.withValue { value -> urlBuilder.addQueryParameter(parameter.name, value) }
            }
        }
        val requestBuilder = Request.Builder().url(urlBuilder.build())
        specification.withPublicHeaders { headers ->
            headers.forEach(requestBuilder::header)
        }

        val secretHeaderNames = linkedSetOf<String>()
        specification.primarySecretHeader?.let { binding ->
            val secretRefId = requireNotNull(specification.profile.primarySecretRefId)
            addSecretHeader(
                requestBuilder = requestBuilder,
                headerName = binding.name,
                secretRefId = secretRefId,
                purpose = ProviderSecretPurpose.API_KEY,
                scheme = binding.scheme,
                now = now,
            )
            secretHeaderNames += binding.name
        }
        specification.profile.withSensitiveHeaderSecretRefs { references ->
            references.forEach { (headerName, secretRefId) ->
                addSecretHeader(
                    requestBuilder = requestBuilder,
                    headerName = headerName,
                    secretRefId = secretRefId,
                    purpose = ProviderSecretPurpose.SENSITIVE_HEADER,
                    scheme = PrimarySecretScheme.RAW,
                    now = now,
                )
                secretHeaderNames += headerName
            }
        }

        when (specification.method) {
            ProviderHttpMethod.GET -> requestBuilder.get()
            ProviderHttpMethod.POST -> requestBuilder.post(
                SensitiveBodyRequestBody(requireNotNull(specification.body)),
            )
        }
        return BuiltRequest(requestBuilder.build(), secretHeaderNames)
    }

    private fun addSecretHeader(
        requestBuilder: Request.Builder,
        headerName: String,
        secretRefId: String,
        purpose: ProviderSecretPurpose,
        scheme: PrimarySecretScheme,
        now: Long,
    ) {
        secretSource.withSecret(secretRefId, purpose, now) { bytes ->
            require(bytes.size in 1..MAX_SECRET_HEADER_BYTES && bytes.all { byte -> isSafeSecretByte(byte, purpose) }) {
                "Secret header material contains invalid bytes."
            }
            val raw = String(bytes, StandardCharsets.US_ASCII)
            val value = when (scheme) {
                PrimarySecretScheme.RAW -> raw
                PrimarySecretScheme.BEARER -> "Bearer $raw"
            }
            requestBuilder.header(headerName, value)
        }
    }

    private fun sanitizeResponse(response: Response, secretHeaderNames: Set<String>): Response {
        val requestBuilder = response.request.newBuilder()
        secretHeaderNames.forEach(requestBuilder::removeHeader)
        val responseBuilder = response.newBuilder().request(requestBuilder.build())
        SENSITIVE_RESPONSE_HEADERS.forEach(responseBuilder::removeHeader)
        return responseBuilder.build()
    }

    private fun classify(error: Exception): StandardErrorCode = when (error) {
        is SecretMaterialUnavailableException -> StandardErrorCode.CREDENTIAL_UNAVAILABLE
        is EndpointRejectedException,
        is RedirectRejectedException,
        is IllegalArgumentException,
        is IllegalStateException,
        -> StandardErrorCode.PROTOCOL_MISMATCH
        is IOException -> NetworkFailureClassifier.classify(error)
        else -> StandardErrorCode.UNKNOWN_RESULT
    }

    private fun requestStateFor(
        error: Exception,
        code: StandardErrorCode,
    ): FailureRequestState = when {
        error is SecretMaterialUnavailableException -> FailureRequestState.NOT_SENT
        error is EndpointRejectedException -> FailureRequestState.NOT_SENT
        error is IllegalArgumentException -> FailureRequestState.NOT_SENT
        error is IllegalStateException -> FailureRequestState.NOT_SENT
        code == StandardErrorCode.DNS_FAILED -> FailureRequestState.NOT_SENT
        code == StandardErrorCode.TLS_FAILED -> FailureRequestState.NOT_SENT
        else -> FailureRequestState.RESULT_UNKNOWN
    }

    private fun recordFailure(
        exchange: ActiveExchange,
        code: StandardErrorCode,
        error: Throwable,
        httpStatus: Int?,
    ) {
        if (!exchange.markFailureRecorded()) return
        record(
            exchange.diagnostic(
                code = ProviderTransportDiagnosticCode.REQUEST_FAILED,
                now = clock(),
                standardErrorCode = code,
                httpStatus = httpStatus,
                error = error,
            ),
        )
    }

    private fun record(diagnostic: ProviderTransportDiagnostic) {
        runCatching { diagnostics.record(diagnostic) }
    }

    private data class BuiltRequest(
        val request: Request,
        val secretHeaderNames: Set<String>,
    )

    private class SensitiveBodyRequestBody(
        private val body: SensitiveHttpBody,
    ) : RequestBody() {
        override fun contentType() = JSON_MEDIA_TYPE

        override fun contentLength(): Long = body.byteCount.toLong()

        override fun writeTo(sink: BufferedSink) {
            body.withBytes(sink::write)
        }
    }

    private class ActiveExchange(
        private val specification: ProviderHttpRequestSpec,
        private val startedAt: Long,
    ) {
        private var cancelAction: (() -> Unit)? = null
        private var cancellationRequested = false
        private var failureRecorded = false

        fun attach(call: Call) = attach(call::cancel)

        fun attach(action: () -> Unit) {
            val cancelImmediately = synchronized(this) {
                cancelAction = action
                cancellationRequested
            }
            if (cancelImmediately) action()
        }

        fun cancel(): Boolean {
            val action = synchronized(this) {
                if (cancellationRequested) return false
                cancellationRequested = true
                cancelAction
            }
            action?.invoke()
            return true
        }

        fun isCancellationRequested(): Boolean = synchronized(this) { cancellationRequested }

        fun markFailureRecorded(): Boolean = synchronized(this) {
            if (failureRecorded) return false
            failureRecorded = true
            true
        }

        fun diagnostic(
            code: ProviderTransportDiagnosticCode,
            now: Long,
            standardErrorCode: StandardErrorCode? = null,
            httpStatus: Int? = null,
            error: Throwable? = null,
        ): ProviderTransportDiagnostic {
            val endpoint = specification.profile.withBaseUrl { it }
            return ProviderTransportDiagnostic(
                timestampEpochMillis = now.coerceAtLeast(0),
                code = code,
                protocol = specification.profile.protocol,
                standardErrorCode = standardErrorCode,
                httpStatus = httpStatus,
                elapsedMillis = (now - startedAt).coerceAtLeast(0),
                connectionId = specification.profile.connectionId,
                endpoint = endpoint,
                requestId = specification.requestId,
                error = error,
            )
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val SENSITIVE_RESPONSE_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "api-key",
            "x-goog-api-key",
            "cookie",
            "set-cookie",
        )

        const val MAX_SECRET_HEADER_BYTES = 16_384

        fun isSafeSecretByte(byte: Byte, purpose: ProviderSecretPurpose): Boolean {
            val minimum = if (purpose == ProviderSecretPurpose.API_KEY) 0x21 else 0x20
            return (byte.toInt() and 0xff) in minimum..0x7e
        }
    }
}
