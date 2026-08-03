package app.zhijuan.provider.openai.chat

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidOpenAiChatAdapterIntegrationTest {
    @Test
    fun deepSeekStyleHttpsStreamReachesSingleTerminalOnAndroid() = runBlocking {
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
            val streamBody =
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"安卓流式\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":4,\"total_tokens\":9}}\n\n" +
                    "data: [DONE]\n\n"
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(Buffer().writeUtf8(streamBody))
                    // Fragment delivery at UTF-8 byte boundaries without fabricating HTTP chunk frames.
                    .throttleBody(2, 1, TimeUnit.MILLISECONDS)
                    .build(),
            )
            val client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .sslSocketFactory(
                    clientCertificates.sslSocketFactory(),
                    clientCertificates.trustManager,
                )
                .build()
            val transport = SecureProviderHttpTransport(
                secretSource = FakeSecretSource(),
                baseClient = client,
            )
            val adapter = OpenAiChatAdapter(
                transport,
                FixedOpenAiChatCompatibilityResolver(OpenAiChatCompatibilityMode.DEEPSEEK),
            )
            val profile = ProviderConnectionProfile.create(
                connectionId = "android-openai-chat-023",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
                baseUrl = server.url("/v1").toString(),
                primarySecretRefId = SECRET_REF,
            )

            val events = adapter.generate(profile, request()).toList()

            val text = events.filterIsInstance<ProviderStreamEvent.TextDelta>()
                .joinToString("") { it.text.withValue { value -> value } }
            assertTrue(events.none { it is ProviderStreamEvent.Failed })
            assertEquals("安卓流式", text)
            assertEquals(1, events.count { it is ProviderStreamEvent.UsageUpdate })
            assertEquals(
                ProviderFinishReason.STOP,
                events.filterIsInstance<ProviderStreamEvent.Completed>().single().reason,
            )
            assertEquals(1, events.count { it is ProviderStreamEvent.Completed })
            val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/v1/chat/completions", recorded.target)
            assertEquals("Bearer $TEST_CREDENTIAL", recorded.headers["Authorization"])
            assertTrue(requireNotNull(recorded.body).utf8().contains("\"max_tokens\":512"))
        } finally {
            server.close()
        }
    }

    private fun request() = GenerationRequest(
        requestId = "android-openai-chat-request-023",
        generationId = "android-generation-023",
        stageId = "android-stage-023",
        attemptId = "android-attempt-023",
        modelId = ProviderModelId.from("android-test-model"),
        prompt = ProviderPrompt(
            listOf(
                PromptPart(PromptLayer.STAGE_CONTRACT, SensitiveProviderText.from("生成一段测试正文。")),
            ),
        ),
        parameters = GenerationParameters(maxOutputTokens = 512),
        structuredOutputSchema = null,
        stream = true,
        timeouts = ProviderTimeoutPolicy(
            connectMillis = 1_000,
            firstByteMillis = 10_000,
            streamIdleMillis = 5_000,
            totalStageMillis = 30_000,
        ),
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
        const val SECRET_REF = "123e4567-e89b-42d3-a456-426614174024"
        const val TEST_CREDENTIAL = "ZHIJUAN_ANDROID_CHAT_CREDENTIAL_023"
    }
}
