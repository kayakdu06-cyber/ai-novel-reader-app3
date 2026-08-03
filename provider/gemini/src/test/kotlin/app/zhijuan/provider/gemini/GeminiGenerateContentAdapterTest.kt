package app.zhijuan.provider.gemini

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.provider.common.CapabilitySupport
import app.zhijuan.provider.common.ConnectionTestResult
import app.zhijuan.provider.common.ConnectionVerificationRequest
import app.zhijuan.provider.common.ConnectionVerificationResult
import app.zhijuan.provider.common.GenerationParameters
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.InMemoryProviderCapabilityStore
import app.zhijuan.provider.common.ModelListResult
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderConnectionVerifier
import app.zhijuan.provider.common.ProviderAdapterRegistry
import app.zhijuan.provider.common.ProviderCapabilityRegistry
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderJsonSchema
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderPrompt
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderRefusalCategory
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.common.ReasoningEffort
import app.zhijuan.provider.common.SensitiveProviderText
import app.zhijuan.provider.transport.ProviderSecretMaterialSource
import app.zhijuan.provider.transport.ProviderSecretPurpose
import app.zhijuan.provider.transport.SecureProviderHttpTransport
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeminiGenerateContentAdapterTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterEach
    fun tearDown() = servers.forEach(MockWebServer::close)

    @Test
    fun `stream uses private header current response format and hides thought parts`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .addHeader("x-goog-request-id", "gemini-remote-025")
                .body(
                    sse(
                        """{"candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"hidden reasoning"},{"text":"\u7b2c\u4e00"}]},"index":0}]}""",
                        """{"candidates":[{"content":{"role":"model","parts":[{"text":"\u7ae0"}]},"finishReason":"STOP","index":0}],"modelVersion":"test-v1"}""",
                        """{"usageMetadata":{"promptTokenCount":20,"cachedContentTokenCount":4,"candidatesTokenCount":8,"thoughtsTokenCount":3,"totalTokenCount":31}}""",
                    ),
                ).build(),
        )

        val events = adapter().generate(
            profile(server),
            request(
                stream = true,
                parameters = GenerationParameters(
                    temperature = 0.7,
                    topP = 0.9,
                    maxOutputTokens = 2_048,
                    seed = 25,
                    reasoningEffort = ReasoningEffort.MEDIUM,
                ),
                schema = ProviderJsonSchema.from(
                    """{"type":"object","properties":{"chapter":{"type":"string"}},"required":["chapter"]}""",
                ),
            ),
        ).toList()

        assertEquals("\u7b2c\u4e00\u7ae0", text(events, structured = true), events.toString())
        assertFalse(events.toString().contains("hidden reasoning"))
        assertEquals(ProviderFinishReason.STOP, events.filterIsInstance<ProviderStreamEvent.Completed>().single().reason)
        val usage = events.filterIsInstance<ProviderStreamEvent.UsageUpdate>().single().usage
        assertEquals(20, usage.inputTokens)
        assertEquals(4, usage.cachedInputTokens)
        assertEquals(8, usage.outputTokens)
        assertEquals(3, usage.reasoningTokens)
        assertEquals(31, usage.totalTokens)

        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/test-model:streamGenerateContent?alt=sse", recorded.target)
        assertEquals(TEST_CREDENTIAL, recorded.headers["x-goog-api-key"])
        assertNull(recorded.url.queryParameter("key"))
        val body = Json.parseToJsonElement(requireNotNull(recorded.body).utf8()).jsonObject
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        assertEquals("user", body["contents"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(body["systemInstruction"]!!.jsonObject.toString().contains("STAGE_CONTRACT"))
        val config = body["generationConfig"]!!.jsonObject
        assertEquals(2_048, config["maxOutputTokens"]!!.jsonPrimitive.int)
        assertEquals("MEDIUM", config["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        assertEquals(
            "application/json",
            config["responseFormat"]!!.jsonObject["text"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content,
        )
        assertTrue(config["responseFormat"]!!.jsonObject["text"]!!.jsonObject["schema"] is kotlinx.serialization.json.JsonObject)
    }

    @Test
    fun `non streaming max tokens preserves partial text and maps length`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"partial"}]},"finishReason":"MAX_TOKENS","index":0}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":16,"totalTokenCount":24}}""",
            ).build(),
        )

        val events = adapter().generate(profile(server), request(stream = false)).toList()

        assertEquals(1, server.requestCount, events.toString())
        assertEquals("partial", text(events), events.toString())
        assertEquals(ProviderFinishReason.LENGTH, events.filterIsInstance<ProviderStreamEvent.Completed>().single().reason)
    }

    @Test
    fun `prompt safety block is a refusal and emits usage first`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"promptFeedback":{"blockReason":"SAFETY","safetyRatings":[{"category":"HARM_CATEGORY_DANGEROUS_CONTENT","probability":"HIGH"}]},"usageMetadata":{"promptTokenCount":12,"totalTokenCount":12}}""",
            ).build(),
        )

        val events = adapter().generate(profile(server), request(stream = false)).toList()

        assertTrue(
            events.indexOfFirst { it is ProviderStreamEvent.UsageUpdate } <
                events.indexOfFirst { it is ProviderStreamEvent.Refused },
            events.toString(),
        )
        assertEquals(ProviderRefusalCategory.SAFETY, events.filterIsInstance<ProviderStreamEvent.Refused>().single().category)
        assertTrue(events.none { it is ProviderStreamEvent.Completed })
    }

    @Test
    fun `candidate policy language and malformed tool reasons never become normal stop`() = runBlocking {
        suspend fun finish(reason: String): ProviderStreamEvent {
            val server = server()
            server.enqueue(
                MockResponse.Builder().code(200).body(
                    """{"candidates":[{"content":{"role":"model","parts":[]},"finishReason":"$reason","index":0}]}""",
                ).build(),
            )
            return adapter().generate(profile(server), request(stream = false)).toList().last()
        }

        assertEquals(ProviderRefusalCategory.POLICY, (finish("RECITATION") as ProviderStreamEvent.Refused).category)
        assertEquals(
            ProviderRefusalCategory.UNSUPPORTED_REQUEST,
            (finish("LANGUAGE") as ProviderStreamEvent.Refused).category,
        )
        assertEquals(
            StandardErrorCode.PROTOCOL_MISMATCH,
            (finish("MALFORMED_FUNCTION_CALL") as ProviderStreamEvent.Failed).code,
        )
    }

    @Test
    fun `missing finish and content after finish fail closed`() = runBlocking {
        val cutServer = server()
        cutServer.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse("""{"candidates":[{"content":{"role":"model","parts":[{"text":"half"}]},"index":0}]}"""),
            ).build(),
        )
        val invalidServer = server()
        invalidServer.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    """{"candidates":[{"content":{"role":"model","parts":[]},"finishReason":"STOP","index":0}]}""",
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"late"}]},"index":0}]}""",
                ),
            ).build(),
        )

        val cut = adapter().generate(profile(cutServer), request(stream = true)).toList()
        val invalid = adapter().generate(profile(invalidServer), request(stream = true)).toList()

        assertEquals("half", text(cut), cut.toString())
        assertEquals(StandardErrorCode.STREAM_INTERRUPTED, cut.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertEquals(StandardErrorCode.PROTOCOL_MISMATCH, invalid.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertEquals(1, invalid.count { it is ProviderStreamEvent.Failed })
    }

    @Test
    fun `HTTP resource exhausted maps retry delay without message leakage`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(429).addHeader("Retry-After", "7").body(
                """{"error":{"code":429,"message":"$ERROR_CANARY","status":"RESOURCE_EXHAUSTED"}}""",
            ).build(),
        )

        val failure = adapter().generate(profile(server), request(stream = false)).toList()
            .filterIsInstance<ProviderStreamEvent.Failed>().single()

        assertEquals(StandardErrorCode.RATE_LIMITED, failure.code)
        assertEquals(429, failure.httpStatus)
        assertEquals(7_000, failure.retryAfterMillis)
        assertEquals(FailureRequestState.PROVIDER_REJECTED, failure.requestState)
        assertFalse(failure.toString().contains(ERROR_CANARY))
    }

    @Test
    fun `ambiguous resource exhausted without retry evidence is treated as quota`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(429).body(
                """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""",
            ).build(),
        )

        val failure = adapter().generate(profile(server), request(stream = false)).toList()
            .filterIsInstance<ProviderStreamEvent.Failed>().single()

        assertEquals(StandardErrorCode.QUOTA_EXHAUSTED, failure.code)
        assertEquals(null, failure.retryAfterMillis)
        assertEquals(FailureRequestState.PROVIDER_REJECTED, failure.requestState)
    }

    @Test
    fun `model list filters unsupported models exposes limits and powers connection test`() = runBlocking {
        val server = server()
        val body = """{"models":[{"name":"models/gemini-a","inputTokenLimit":1048576,"outputTokenLimit":65536,"supportedGenerationMethods":["generateContent","countTokens"]},{"name":"models/embed-only","inputTokenLimit":2048,"supportedGenerationMethods":["embedContent"]},{"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]}],"nextPageToken":""}"""
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).body(body).build()) }

        val models = adapter().listModels(profile(server)) as ModelListResult.Success
        val connection = adapter().testConnection(profile(server)) as ConnectionTestResult.Success

        assertEquals(1, models.models.size)
        assertEquals(1_048_576, models.models.single().contextLimitHint)
        assertEquals(65_536, models.models.single().maxOutputTokensHint)
        assertFalse(connection.minimalGenerationVerified)
        repeat(2) {
            val recorded = server.takeRequest()
            assertEquals("/v1beta/models?pageSize=1000", recorded.target)
            assertEquals(TEST_CREDENTIAL, recorded.headers["x-goog-api-key"])
        }
    }

    @Test
    fun `bounded verifier composes model discovery and one minimal Gemini generation`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"models":[{"name":"models/test-model","inputTokenLimit":1048576,"outputTokenLimit":65536,"supportedGenerationMethods":["generateContent"]}]}""",
            ).build(),
        )
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"OK"}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":1,"totalTokenCount":13}}""",
                ),
            ).build(),
        )
        val adapter = adapter()
        val capabilityRegistry = ProviderCapabilityRegistry(
            InMemoryProviderCapabilityStore(),
            clock = { 1_800_000_000_000 },
        )
        val verifier = ProviderConnectionVerifier(
            ProviderAdapterRegistry(listOf(adapter)),
            capabilityRegistry,
            clock = { 1_800_000_000_000 },
        )

        val result = verifier.verify(
            ConnectionVerificationRequest(
                profile = profile(server),
                selectedModelId = ProviderModelId.from("models/test-model"),
                verifyMinimalGeneration = true,
                minimalGenerationCostAcknowledged = true,
            ),
        )

        val report = (result as ConnectionVerificationResult.Completed).report
        assertTrue(report.modelListVerified)
        assertTrue(report.minimalGenerationVerified)
        assertTrue(report.usageObserved)
        assertEquals("/v1beta/models?pageSize=1000", server.takeRequest().target)
        val probeRequest = server.takeRequest()
        assertEquals("/v1beta/models/test-model:streamGenerateContent?alt=sse", probeRequest.target)
        val body = Json.parseToJsonElement(requireNotNull(probeRequest.body).utf8()).jsonObject
        assertEquals(16, body["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
        assertFalse(body.toString().contains("PRIVATE_NOVEL"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `invalid parameter model path and unknown structured capability fail before network`() = runBlocking {
        val server = server()
        val invalidTemperature = adapter().generate(
            profile(server),
            request(stream = false, parameters = GenerationParameters(temperature = 1.5)),
        ).toList()
        val invalidModel = adapter().generate(
            profile(server),
            request(stream = false, model = "models/a/b"),
        ).toList()
        val unknownStructured = GeminiGenerateContentAdapter(
            SecureProviderHttpTransport(FakeSecretSource()),
        ).generate(
            profile(server),
            request(
                stream = false,
                schema = ProviderJsonSchema.from("""{"type":"object"}"""),
            ),
        ).toList()

        listOf(invalidTemperature, invalidModel, unknownStructured).forEach {
            assertEquals(StandardErrorCode.PROTOCOL_MISMATCH, it.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        }
        assertEquals(0, server.requestCount)
        assertEquals(null, server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `malformed candidate and named SSE event produce one protocol failure`() = runBlocking {
        val malformedServer = server()
        malformedServer.enqueue(
            MockResponse.Builder().code(200).body(
                """{"candidates":[{"content":{"role":"assistant","parts":[{"text":"bad"}]},"finishReason":"STOP","index":0}]}""",
            ).build(),
        )
        val namedServer = server()
        namedServer.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                "event: future\ndata: {\"usageMetadata\":{\"promptTokenCount\":1}}\n\n",
            ).build(),
        )

        val malformed = adapter().generate(profile(malformedServer), request(stream = false)).toList()
        val named = adapter().generate(profile(namedServer), request(stream = true)).toList()

        assertEquals(StandardErrorCode.PROTOCOL_MISMATCH, malformed.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertEquals(StandardErrorCode.PROTOCOL_MISMATCH, named.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertEquals(1, named.count { it is ProviderStreamEvent.Failed })
    }

    private fun adapter() = GeminiGenerateContentAdapter(
        transport = SecureProviderHttpTransport(FakeSecretSource()),
        capabilityResolver = GeminiCapabilityResolver { _, modelId, verifiedAt, version ->
            geminiCapabilities(modelId, verifiedAt, version, CapabilitySupport.SUPPORTED)
        },
        clock = { 1_800_000_000_000 },
    )

    private fun profile(server: MockWebServer) = ProviderConnectionProfile.create(
        connectionId = "connection-gemini-025",
        protocol = ProviderProtocol.GEMINI_GENERATE_CONTENT,
        baseUrl = server.url("/v1beta").toString(),
        primarySecretRefId = TEST_SECRET_REF,
        explicitLocalCleartext = true,
    )

    private fun request(
        stream: Boolean,
        parameters: GenerationParameters = GenerationParameters(maxOutputTokens = 1_024),
        schema: ProviderJsonSchema? = null,
        model: String = "test-model",
    ) = GenerationRequest(
        requestId = "request-gemini-025",
        generationId = "generation-025",
        stageId = "stage-025",
        attemptId = "attempt-025",
        modelId = ProviderModelId.from(model),
        prompt = ProviderPrompt(
            listOf(
                PromptPart(PromptLayer.APPLICATION_HARD_RULES, SensitiveProviderText.from("Keep local context private.")),
                PromptPart(PromptLayer.STAGE_CONTRACT, SensitiveProviderText.from("Write one complete chapter.")),
                PromptPart(PromptLayer.USER_REQUEST, SensitiveProviderText.from("Begin chapter one.")),
            ),
        ),
        parameters = parameters,
        structuredOutputSchema = schema,
        stream = stream,
        timeouts = ProviderTimeoutPolicy(1_000, 5_000, 2_000, 10_000),
        idempotencyKey = null,
    )

    private fun server() = MockWebServer().also { it.start(); servers += it }

    private fun text(events: List<ProviderStreamEvent>, structured: Boolean = false) =
        if (structured) {
            events.filterIsInstance<ProviderStreamEvent.StructuredDelta>()
                .joinToString("") { it.fragment.withValue { value -> value } }
        } else {
            events.filterIsInstance<ProviderStreamEvent.TextDelta>()
                .joinToString("") { it.text.withValue { value -> value } }
        }

    private fun sse(vararg data: String) = data.joinToString("") { "data: $it\n\n" }

    private class FakeSecretSource : ProviderSecretMaterialSource {
        override fun <T> withSecret(
            secretRefId: String,
            purpose: ProviderSecretPurpose,
            now: Long,
            block: (ByteArray) -> T,
        ): T {
            require(secretRefId == TEST_SECRET_REF)
            val bytes = TEST_CREDENTIAL.toByteArray()
            return try { block(bytes) } finally { bytes.fill(0) }
        }
    }

    private companion object {
        const val TEST_SECRET_REF = "123e4567-e89b-42d3-a456-426614174025"
        const val TEST_CREDENTIAL = "ZHIJUAN_GEMINI_TEST_CREDENTIAL_025"
        const val ERROR_CANARY = "ZHIJUAN_GEMINI_ERROR_CANARY_025"
    }
}
