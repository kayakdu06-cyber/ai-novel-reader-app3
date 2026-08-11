package app.zhijuan.provider.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.diagnostics.DiagnosticCode
import app.zhijuan.core.diagnostics.DiagnosticSnapshotResult
import app.zhijuan.core.diagnostics.EncryptedDiagnosticStore
import app.zhijuan.core.security.AndroidSecretStore
import app.zhijuan.core.security.SecretPurpose
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidProviderTransportIntegrationTest {
    @Test
    fun realKeystoreSecretsReachOnlyHttpsServerAndDiagnosticsContainOnlyHashes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val secretStore = AndroidSecretStore(context)
        val diagnosticStore = EncryptedDiagnosticStore(context)
        diagnosticStore.clear()
        val now = System.currentTimeMillis()
        val primary = secretStore.createAndClear(
            SecretPurpose.API_KEY,
            PRIMARY_SECRET.toByteArray(),
            now,
        )
        val custom = secretStore.createAndClear(
            SecretPurpose.SENSITIVE_HEADER,
            CUSTOM_SECRET.toByteArray(),
            now,
        )
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val server = MockWebServer()
        try {
            server.useHttps(serverCertificates.sslSocketFactory())
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            val profile = ProviderConnectionProfile.create(
                connectionId = CONNECTION_ID,
                protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
                baseUrl = server.url("/v1").toString(),
                primarySecretRefId = primary.secretRefId,
                sensitiveHeaderSecretRefs = mapOf("X-Api-Key" to custom.secretRefId),
            )
            val transport = SecureProviderHttpTransport(
                secretSource = AndroidProviderSecretMaterialSource(secretStore),
                diagnostics = EncryptedProviderTransportDiagnosticSink(
                    store = diagnosticStore,
                    androidApiLevel = android.os.Build.VERSION.SDK_INT,
                ),
                baseClient = OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .sslSocketFactory(
                        clientCertificates.sslSocketFactory(),
                        clientCertificates.trustManager,
                    )
                    .build(),
            )
            val request = ProviderHttpRequestSpec(
                requestId = REQUEST_ID,
                profile = profile,
                method = ProviderHttpMethod.POST,
                pathSegments = listOf("generate"),
                primarySecretHeader = PrimarySecretHeader(
                    "Authorization",
                    PrimarySecretScheme.BEARER,
                ),
                body = SensitiveHttpBody.fromUtf8AndClear(BODY_CANARY.toCharArray()),
                maximumResponseBytes = 1024,
            )

            val result = transport.open(request, timeouts()) as ProviderHttpOpenResult.Opened
            assertEquals("ok", result.response.withBodySource { it.readUtf8() })
            result.response.close()
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
            requireNotNull(recorded)
            assertEquals("Bearer $PRIMARY_SECRET", recorded.headers["Authorization"])
            assertEquals(CUSTOM_SECRET, recorded.headers["X-Api-Key"])
            assertEquals(BODY_CANARY, requireNotNull(recorded.body).utf8())

            val snapshot = diagnosticStore.snapshot() as DiagnosticSnapshotResult.Available
            assertEquals(
                listOf(DiagnosticCode.REQUEST_STARTED, DiagnosticCode.RESPONSE_OPENED),
                snapshot.events.map { it.code },
            )
            snapshot.events.forEach { event ->
                assertEquals(3, event.correlationHashes.size)
                assertTrue(event.correlationHashes.values.all { it.matches(Regex("[0-9a-f]{24}")) })
                assertFalse(event.toString().contains(CONNECTION_ID))
                assertFalse(event.toString().contains(PRIMARY_SECRET))
                assertFalse(event.toString().contains(BODY_CANARY))
            }
        } finally {
            server.close()
            runCatching { secretStore.revoke(primary.secretRefId, System.currentTimeMillis()) }
            runCatching { secretStore.revoke(custom.secretRefId, System.currentTimeMillis()) }
            diagnosticStore.clear()
        }
    }

    private fun timeouts() = ProviderTimeoutPolicy(
        connectMillis = 1_000,
        firstByteMillis = 10_000,
        streamIdleMillis = 5_000,
        totalStageMillis = 30_000,
    )

    private companion object {
        const val CONNECTION_ID = "transport-android-connection"
        const val REQUEST_ID = "transport-android-request"
        const val PRIMARY_SECRET = "ZHIJUAN_ANDROID_PRIMARY_CREDENTIAL_021"
        const val CUSTOM_SECRET = "ZHIJUAN_ANDROID_CUSTOM_CREDENTIAL_021"
        const val BODY_CANARY = "ZHIJUAN_ANDROID_BODY_CANARY_021"
    }
}
