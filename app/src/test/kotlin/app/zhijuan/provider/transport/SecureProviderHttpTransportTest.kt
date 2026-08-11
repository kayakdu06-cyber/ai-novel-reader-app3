package app.zhijuan.provider.transport

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecureProviderHttpTransportTest {
    private val servers = mutableListOf<MockWebServer>()
    private val certificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .build()
    private val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(certificate)
        .build()
    private val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate)
        .build()

    @AfterEach
    fun tearDown() {
        servers.forEach(MockWebServer::close)
    }

    @Test
    fun `request injects leased secrets sends body and exposes only bounded response`() {
        val server = httpsServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("X-Request-Id", "remote-request")
                .addHeader("Set-Cookie", "session=private")
                .body("response-ok")
                .build(),
        )
        val secrets = FakeSecretSource()
        val diagnosticSink = RecordingDiagnosticSink()
        val transport = transport(secrets, diagnosticSink)
        val source = REQUEST_BODY_CANARY.toCharArray()
        val body = SensitiveHttpBody.fromUtf8AndClear(source)
        assertTrue(source.all { it == '\u0000' })
        val spec = specification(server, body = body)

        val result = transport.open(spec, timeouts())

        val opened = result as ProviderHttpOpenResult.Opened
        val recorded = server.takeRequest()
        assertEquals("Bearer $PRIMARY_SECRET", recorded.headers["Authorization"])
        assertEquals(CUSTOM_SECRET, recorded.headers["X-Api-Key"])
        assertEquals("transport-test", recorded.headers["X-Client-Version"])
        assertEquals(REQUEST_BODY_CANARY, requireNotNull(recorded.body).utf8())
        assertEquals("remote-request", opened.response.withHeaderValue("X-Request-Id") { it })
        assertThrows(IllegalArgumentException::class.java) {
            opened.response.withHeaderValue("Set-Cookie") { it }
        }
        assertEquals("response-ok", opened.response.withBodySource { it.readUtf8() })
        assertFalse(opened.response.toString().contains("localhost"))
        assertFalse(opened.response.toString().contains(PRIMARY_SECRET))
        opened.response.close()

        assertTrue(secrets.leasedBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
        assertEquals(
            listOf(
                ProviderTransportDiagnosticCode.REQUEST_STARTED,
                ProviderTransportDiagnosticCode.RESPONSE_OPENED,
            ),
            diagnosticSink.events.map(ProviderTransportDiagnostic::code),
        )
        assertEquals(ProviderTransportCancellationResult.NOT_ACTIVE, transport.cancel(spec.requestId))
    }

    @Test
    fun `unavailable secret fails before network and never leaks exception message`() {
        val server = httpsServer()
        val diagnostics = RecordingDiagnosticSink()
        val source = object : ProviderSecretMaterialSource {
            override fun <T> withSecret(
                secretRefId: String,
                purpose: ProviderSecretPurpose,
                now: Long,
                block: (ByteArray) -> T,
            ): T = throw SecretMaterialUnavailableException(IllegalStateException(ERROR_MESSAGE_CANARY))
        }
        val transport = transport(source, diagnostics)

        val result = transport.open(specification(server), timeouts())

        val failureResult = result as ProviderHttpOpenResult.Failed
        assertEquals(
            StandardErrorCode.CREDENTIAL_UNAVAILABLE,
            failureResult.failure.code,
        )
        assertEquals(FailureRequestState.NOT_SENT, failureResult.failure.requestState)
        assertEquals(0, server.requestCount)
        val failure = diagnostics.events.last()
        assertEquals(ProviderTransportDiagnosticCode.REQUEST_FAILED, failure.code)
        assertFalse(failure.toString().contains(ERROR_MESSAGE_CANARY))
        assertFalse(failure.toString().contains("localhost"))
    }

    @Test
    fun `unsafe public headers query credentials and traversal are rejected locally`() {
        val server = httpsServer()
        val profile = profile(server)

        assertThrows(IllegalArgumentException::class.java) {
            ProviderHttpRequestSpec(
                requestId = "unsafe-header",
                profile = profile,
                method = ProviderHttpMethod.GET,
                pathSegments = listOf("models"),
                publicHeaders = mapOf("Authorization" to "public-value"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublicQueryParameter("api_key", "query-value")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderHttpRequestSpec(
                requestId = "unsafe-path",
                profile = profile,
                method = ProviderHttpMethod.GET,
                pathSegments = listOf(".."),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrimarySecretHeader("X-Trace", PrimarySecretScheme.RAW)
        }
        val rejectedBody = SensitiveHttpBody.fromUtf8AndClear(REQUEST_BODY_CANARY.toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            ProviderHttpRequestSpec(
                requestId = "unsafe-body-path",
                profile = profile,
                method = ProviderHttpMethod.POST,
                pathSegments = listOf(".."),
                body = rejectedBody,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            rejectedBody.withBytes { it.size }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `paid POST redirect is not replayed`() {
        val server = httpsServer()
        server.enqueue(
            MockResponse.Builder()
                .code(307)
                .addHeader("Location", "/second-paid-request")
                .build(),
        )
        val transport = transport(FakeSecretSource())

        val result = transport.open(specification(server), timeouts())

        assertEquals(
            StandardErrorCode.PROTOCOL_MISMATCH,
            (result as ProviderHttpOpenResult.Failed).failure.code,
        )
        assertEquals(1, server.requestCount)
        assertEquals("/v1/generate?alt=sse", server.takeRequest().target)
    }

    @Test
    fun `cross origin GET redirect never sends either secret to target`() {
        val sourceServer = httpsServer()
        val targetServer = httpsServer()
        sourceServer.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", targetServer.url("/capture"))
                .build(),
        )
        val transport = transport(FakeSecretSource())
        val spec = specification(sourceServer, method = ProviderHttpMethod.GET, body = null)

        val result = transport.open(spec, timeouts())

        assertEquals(
            StandardErrorCode.PROTOCOL_MISMATCH,
            (result as ProviderHttpOpenResult.Failed).failure.code,
        )
        assertEquals(1, sourceServer.requestCount)
        assertEquals(0, targetServer.requestCount)
        assertEquals(null, targetServer.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `active request cancellation is idempotent and stops the call`() {
        val server = httpsServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .headersDelay(10, TimeUnit.SECONDS)
                .body("late")
                .build(),
        )
        val diagnostics = RecordingDiagnosticSink()
        val transport = transport(FakeSecretSource(), diagnostics)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<ProviderHttpOpenResult> {
                transport.open(specification(server, requestId = "cancel-request"), longTimeouts())
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

            assertEquals(
                ProviderTransportCancellationResult.CANCELLATION_REQUESTED,
                transport.cancel("cancel-request"),
            )
            assertEquals(
                ProviderTransportCancellationResult.ALREADY_REQUESTED,
                transport.cancel("cancel-request"),
            )
            assertTrue(future.get(5, TimeUnit.SECONDS) is ProviderHttpOpenResult.Cancelled)
            assertEquals(ProviderTransportCancellationResult.NOT_ACTIVE, transport.cancel("cancel-request"))
            assertEquals(
                1,
                diagnostics.events.count { it.code == ProviderTransportDiagnosticCode.REQUEST_CANCELLED },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `declared and streamed response limits fail closed`() {
        val declared = httpsServer()
        declared.enqueue(MockResponse.Builder().code(200).body("123456").build())
        val first = transport(FakeSecretSource()).open(
            specification(declared, maximumResponseBytes = 4),
            timeouts(),
        )
        assertEquals(
            StandardErrorCode.PROTOCOL_MISMATCH,
            (first as ProviderHttpOpenResult.Failed).failure.code,
        )

        val streamed = httpsServer()
        streamed.enqueue(MockResponse.Builder().code(200).chunkedBody("123456", 2).build())
        val diagnostics = RecordingDiagnosticSink()
        val transport = transport(FakeSecretSource(), diagnostics)
        val second = transport.open(
            specification(streamed, requestId = "bounded-stream", maximumResponseBytes = 4),
            timeouts(),
        ) as ProviderHttpOpenResult.Opened
        assertThrows(ProtocolException::class.java) {
            second.response.withBodySource { it.readUtf8() }
        }
        assertEquals(ProviderTransportCancellationResult.NOT_ACTIVE, transport.cancel("bounded-stream"))
        assertEquals(
            1,
            diagnostics.events.count { it.code == ProviderTransportDiagnosticCode.REQUEST_FAILED },
        )
    }

    @Test
    fun `duplicate active request id fails without replacing original call`() {
        val server = httpsServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .headersDelay(10, TimeUnit.SECONDS)
                .body("late")
                .build(),
        )
        val transport = transport(FakeSecretSource())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val original = executor.submit<ProviderHttpOpenResult> {
                transport.open(specification(server, requestId = "same-request"), longTimeouts())
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val duplicate = transport.open(
                specification(server, requestId = "same-request"),
                longTimeouts(),
            )

            assertTrue(duplicate is ProviderHttpOpenResult.AlreadyActive)
            assertEquals(
                ProviderTransportCancellationResult.CANCELLATION_REQUESTED,
                transport.cancel("same-request"),
            )
            assertTrue(original.get(5, TimeUnit.SECONDS) is ProviderHttpOpenResult.Cancelled)
            assertEquals(1, server.requestCount)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun transport(
        secrets: ProviderSecretMaterialSource,
        diagnostics: ProviderTransportDiagnosticSink = NoOpProviderTransportDiagnosticSink,
    ): SecureProviderHttpTransport = SecureProviderHttpTransport(
        secretSource = secrets,
        diagnostics = diagnostics,
        baseClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build(),
        clock = System::currentTimeMillis,
    )

    private fun specification(
        server: MockWebServer,
        requestId: String = "request-transport-1",
        method: ProviderHttpMethod = ProviderHttpMethod.POST,
        body: SensitiveHttpBody? = if (method == ProviderHttpMethod.POST) {
            SensitiveHttpBody.fromUtf8AndClear(REQUEST_BODY_CANARY.toCharArray())
        } else {
            null
        },
        maximumResponseBytes: Long = 1024,
    ) = ProviderHttpRequestSpec(
        requestId = requestId,
        profile = profile(server),
        method = method,
        pathSegments = if (method == ProviderHttpMethod.POST) listOf("generate") else listOf("models"),
        queryParameters = listOf(PublicQueryParameter("alt", "sse")),
        publicHeaders = mapOf("X-Client-Version" to "transport-test"),
        primarySecretHeader = PrimarySecretHeader("Authorization", PrimarySecretScheme.BEARER),
        body = body,
        maximumResponseBytes = maximumResponseBytes,
    )

    private fun profile(server: MockWebServer) = ProviderConnectionProfile.create(
        connectionId = "connection-transport-1",
        protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
        baseUrl = server.url("/v1").toString(),
        primarySecretRefId = PRIMARY_REF,
        sensitiveHeaderSecretRefs = mapOf("X-Api-Key" to CUSTOM_REF),
    )

    private fun httpsServer(): MockWebServer = MockWebServer().also { server ->
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
        servers += server
    }

    private fun timeouts() = ProviderTimeoutPolicy(
        connectMillis = 1_000,
        firstByteMillis = 5_000,
        streamIdleMillis = 1_000,
        totalStageMillis = 10_000,
    )

    private fun longTimeouts() = ProviderTimeoutPolicy(
        connectMillis = 1_000,
        firstByteMillis = 20_000,
        streamIdleMillis = 5_000,
        totalStageMillis = 30_000,
    )

    private class FakeSecretSource : ProviderSecretMaterialSource {
        val leasedBuffers = mutableListOf<ByteArray>()

        override fun <T> withSecret(
            secretRefId: String,
            purpose: ProviderSecretPurpose,
            now: Long,
            block: (ByteArray) -> T,
        ): T {
            val value = when (secretRefId) {
                PRIMARY_REF -> PRIMARY_SECRET
                CUSTOM_REF -> CUSTOM_SECRET
                else -> throw IOException("Unknown fixture reference.")
            }.toByteArray()
            leasedBuffers += value
            return try {
                block(value)
            } finally {
                value.fill(0)
            }
        }
    }

    private class RecordingDiagnosticSink : ProviderTransportDiagnosticSink {
        val events = mutableListOf<ProviderTransportDiagnostic>()

        override fun record(diagnostic: ProviderTransportDiagnostic) {
            events += diagnostic
        }
    }

    private companion object {
        const val PRIMARY_REF = "123e4567-e89b-42d3-a456-426614174020"
        const val CUSTOM_REF = "123e4567-e89b-42d3-a456-426614174021"
        const val PRIMARY_SECRET = "ZHIJUAN_PRIMARY_CREDENTIAL_021"
        const val CUSTOM_SECRET = "ZHIJUAN_CUSTOM_CREDENTIAL_021"
        const val REQUEST_BODY_CANARY = "ZHIJUAN_TRANSPORT_BODY_CANARY_021"
        const val ERROR_MESSAGE_CANARY = "ZHIJUAN_TRANSPORT_ERROR_CANARY_021"
    }
}
