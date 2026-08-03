package app.zhijuan.provider.openai.responses

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.provider.common.GenerationParameters
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderPrompt
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.common.SensitiveProviderText
import app.zhijuan.provider.transport.ProviderSecretMaterialSource
import app.zhijuan.provider.transport.ProviderSecretPurpose
import app.zhijuan.provider.transport.SecureProviderHttpTransport
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidOpenAiResponsesAdapterIntegrationTest {
    @Test
    fun responsesHttpsStreamSurvivesUtf8ByteFragmentationOnAndroid() = runBlocking {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val server = MockWebServer()
        try {
            server.useHttps(serverCertificates.sslSocketFactory())
            server.start()
            val expected = "\u5b89\u5353\u6d41\u5f0f"
            val body =
                "event: response.created\n" +
                    "data: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_android_022\"}}\n\n" +
                    "event: response.output_text.delta\n" +
                    "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"delta\":\"$expected\"}\n\n" +
                    "event: response.completed\n" +
                    "data: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"id\":\"resp_android_022\",\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":5,\"output_tokens\":4,\"total_tokens\":9}}}\n\n"
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(Buffer().writeUtf8(body))
                    .throttleBody(2, 1, TimeUnit.MILLISECONDS)
                    .build(),
            )
            val client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
            val adapter = OpenAiResponsesAdapter(
                SecureProviderHttpTransport(
                    secretSource = FakeSecretSource(),
                    baseClient = client,
                ),
            )
            val profile = ProviderConnectionProfile.create(
                connectionId = "android-openai-responses-022",
                protocol = ProviderProtocol.OPENAI_RESPONSES,
                baseUrl = server.url("/v1").toString(),
                primarySecretRefId = SECRET_REF,
            )

            val events = adapter.generate(profile, request()).toList()

            val text = events.filterIsInstance<ProviderStreamEvent.TextDelta>()
                .joinToString("") { it.text.withValue { value -> value } }
            assertEquals(expected, text)
            assertFalse(events.any { it is ProviderStreamEvent.Failed })
            assertEquals(1, events.count { it is ProviderStreamEvent.UsageUpdate })
            assertEquals(
                ProviderFinishReason.STOP,
                events.filterIsInstance<ProviderStreamEvent.Completed>().single().reason,
            )
            assertEquals(1, events.count { it is ProviderStreamEvent.Completed })
            val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/v1/responses", recorded.target)
            assertEquals("Bearer $TEST_CREDENTIAL", recorded.headers["Authorization"])
            val requestBody = requireNotNull(recorded.body).utf8()
            assertTrue(requestBody.contains("\"store\":false"))
            assertTrue(requestBody.contains("\"max_output_tokens\":512"))
        } finally {
            server.close()
        }
    }

    private fun request() = GenerationRequest(
        requestId = "android-openai-responses-request-022",
        generationId = "android-generation-022",
        stageId = "android-stage-022",
        attemptId = "android-attempt-022",
        modelId = ProviderModelId.from("android-test-model"),
        prompt = ProviderPrompt(
            listOf(
                PromptPart(PromptLayer.STAGE_CONTRACT, SensitiveProviderText.from("Generate test text.")),
            ),
        ),
        parameters = GenerationParameters(maxOutputTokens = 512),
        structuredOutputSchema = null,
        stream = true,
        timeouts = ProviderTimeoutPolicy(1_000, 10_000, 5_000, 30_000),
        idempotencyKey = null,
    )

    private class FakeSecretSource : ProviderSecretMaterialSource {
        override fun <T> withSecret(
            secretRefId: String,
            purpose: ProviderSecretPurpose,
            now: Long,
            block: (ByteArray) -> T,
        ): T {
            require(secretRefId == SECRET_REF)
            val bytes = TEST_CREDENTIAL.toByteArray()
            return try {
                block(bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }

    private companion object {
        const val SECRET_REF = "123e4567-e89b-42d3-a456-426614174022"
        const val TEST_CREDENTIAL = "ZHIJUAN_ANDROID_RESPONSES_CREDENTIAL_022"
    }
}
