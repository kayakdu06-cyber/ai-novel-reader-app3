package app.zhijuan.core.network

import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecureRedirectExecutorTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterEach
    fun tearDown() {
        servers.forEach(MockWebServer::close)
    }

    @Test
    fun `factory disables automatic redirects and connection retries`() {
        val client = SecureOkHttpClientFactory.create()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test
    fun `same origin https GET redirect is followed and retains header`() {
        val fixture = httpsFixture()
        fixture.server.enqueue(redirect("/final"))
        fixture.server.enqueue(response(200, "ok"))
        val request = Request.Builder()
            .url(fixture.server.url("/start"))
            .header(AUTHORIZATION, CANARY)
            .build()

        SecureRedirectExecutor(fixture.client).execute(request).use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
        }
        assertEquals(CANARY, fixture.server.takeRequest().headers[AUTHORIZATION])
        assertEquals(CANARY, fixture.server.takeRequest().headers[AUTHORIZATION])
    }

    @Test
    fun `cross origin redirect is rejected before target receives any secret`() {
        val source = httpsFixture()
        val target = MockWebServer().also { server -> server.start(); servers += server }
        val targetUrl = target.url("/capture").newBuilder().scheme("https").build()
        source.server.enqueue(redirect(targetUrl.toString()))
        val request = Request.Builder()
            .url(source.server.url("/start"))
            .header(AUTHORIZATION, CANARY)
            .header("X-Api-Key", CANARY)
            .build()

        val error = assertThrows(RedirectRejectedException::class.java) {
            SecureRedirectExecutor(source.client).execute(request)
        }

        assertEquals(RedirectRejectionReason.CROSS_ORIGIN, error.reason)
        assertEquals(0, target.requestCount)
        assertNull(target.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `https downgrade redirect is rejected`() {
        val fixture = httpsFixture()
        fixture.server.enqueue(redirect("http://example.com/capture"))

        val error = assertThrows(RedirectRejectedException::class.java) {
            SecureRedirectExecutor(fixture.client).execute(
                Request.Builder().url(fixture.server.url("/start")).header(AUTHORIZATION, CANARY).build(),
            )
        }

        assertEquals(RedirectRejectionReason.CLEARTEXT_OR_DOWNGRADE, error.reason)
    }

    @Test
    fun `redirect target with embedded credentials is rejected`() {
        val fixture = httpsFixture()
        val target = fixture.server.url("/capture").newBuilder()
            .username("redirect-user")
            .password("redirect-password")
            .build()
        fixture.server.enqueue(redirect(target.toString()))

        val error = assertThrows(RedirectRejectedException::class.java) {
            SecureRedirectExecutor(fixture.client).execute(
                Request.Builder().url(fixture.server.url("/start")).header(AUTHORIZATION, CANARY).build(),
            )
        }

        assertEquals(RedirectRejectionReason.MISSING_OR_INVALID_LOCATION, error.reason)
        assertEquals(1, fixture.server.requestCount)
    }

    @Test
    fun `POST redirect is never replayed automatically`() {
        val fixture = httpsFixture()
        fixture.server.enqueue(redirect("/paid-work", code = 307))
        val request = Request.Builder()
            .url(fixture.server.url("/generate"))
            .header(AUTHORIZATION, CANARY)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        val error = assertThrows(RedirectRejectedException::class.java) {
            SecureRedirectExecutor(fixture.client).execute(request)
        }

        assertEquals(RedirectRejectionReason.NON_IDEMPOTENT_REQUEST, error.reason)
        assertEquals(1, fixture.server.requestCount)
    }

    @Test
    fun `redirect loop and limit are rejected`() {
        val loop = httpsFixture()
        loop.server.enqueue(redirect("/start"))
        val loopError = assertThrows(RedirectRejectedException::class.java) {
            SecureRedirectExecutor(loop.client).execute(Request.Builder().url(loop.server.url("/start")).build())
        }
        assertEquals(RedirectRejectionReason.LOOP, loopError.reason)

        val limit = httpsFixture()
        limit.server.enqueue(redirect("/one"))
        limit.server.enqueue(redirect("/two"))
        val limitError = assertThrows(RedirectRejectedException::class.java) {
            SecureRedirectExecutor(limit.client, maximumRedirects = 1)
                .execute(Request.Builder().url(limit.server.url("/start")).build())
        }
        assertEquals(RedirectRejectionReason.TOO_MANY_REDIRECTS, limitError.reason)
    }

    @Test
    fun `untrusted certificate is classified as non retryable TLS failure`() {
        val fixture = httpsFixture(trustServerCertificate = false)
        fixture.server.enqueue(response(200, "should not be read"))

        val error = assertThrows(IOException::class.java) {
            SecureRedirectExecutor(fixture.client)
                .execute(Request.Builder().url(fixture.server.url("/tls")).build())
        }

        assertEquals(
            app.zhijuan.core.model.StandardErrorCode.TLS_FAILED,
            NetworkFailureClassifier.classify(error),
            "error=${error::class.qualifiedName} cause=${error.cause?.let { it::class.qualifiedName }}",
        )
    }

    @Test
    fun `DNS failure is classified without a network lookup`() {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .dns(Dns { throw UnknownHostException("fixture") })
            .build()

        val error = assertThrows(IOException::class.java) {
            SecureRedirectExecutor(client)
                .execute(Request.Builder().url("https://example.invalid/v1").build())
        }

        assertEquals(app.zhijuan.core.model.StandardErrorCode.DNS_FAILED, NetworkFailureClassifier.classify(error))
    }

    @Test
    fun `redacted summary omits values query fragment and optional host`() {
        val request = Request.Builder()
            .url("https://api.example.com:8443/v1/models?page=2#top")
            .header(AUTHORIZATION, CANARY)
            .header("X-Api-Key", CANARY)
            .build()

        val visibleHost = RedactedNetworkSummary.from(request)
        val hiddenHost = RedactedNetworkSummary.from(request, hideHost = true)

        assertEquals("https://api.example.com:8443/v1/models", visibleHost.url)
        assertEquals("https://<hidden>:8443/v1/models", hiddenHost.url)
        assertTrue(AUTHORIZATION in visibleHost.headerNames)
        assertFalse(visibleHost.toString().contains(CANARY))
        assertFalse(hiddenHost.toString().contains("api.example.com"))
    }

    private fun httpsFixture(trustServerCertificate: Boolean = true): HttpsFixture {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val server = MockWebServer().also { mockServer ->
            mockServer.useHttps(serverCertificates.sslSocketFactory())
            mockServer.start()
            servers += mockServer
        }
        val builder = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
        if (trustServerCertificate) {
            val clientCertificates = HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate)
                .build()
            builder.sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        }
        return HttpsFixture(server, builder.build())
    }

    private fun redirect(location: String, code: Int = 302): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Location", location)
        .build()

    private fun response(code: Int, body: String): MockResponse = MockResponse.Builder()
        .code(code)
        .body(body)
        .build()

    private data class HttpsFixture(
        val server: MockWebServer,
        val client: OkHttpClient,
    )

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val CANARY = "Bearer test-canary"
    }
}
