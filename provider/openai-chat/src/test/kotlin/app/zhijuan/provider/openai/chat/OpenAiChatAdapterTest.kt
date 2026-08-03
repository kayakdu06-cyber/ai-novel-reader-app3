package app.zhijuan.provider.openai.chat

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiChatAdapterTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterEach
    fun tearDown() {
        servers.forEach(MockWebServer::close)
    }

    @Test
    fun `DeepSeek SSE keeps usage before terminal and ignores reasoning content`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .addHeader("X-Request-Id", "remote-deepseek-023")
                .chunkedBody(
                    sse(
                        """{"id":"chat-1","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"hidden","content":"第一"},"finish_reason":null}],"usage":null}""",
                        """{"id":"chat-1","choices":[{"index":0,"delta":{"content":"章"},"finish_reason":"stop"}],"usage":null}""",
                        """{"id":"chat-1","choices":[],"usage":{"prompt_tokens":120,"completion_tokens":8,"total_tokens":128,"prompt_cache_hit_tokens":90,"prompt_cache_miss_tokens":30,"completion_tokens_details":{"reasoning_tokens":4}}}""",
                        "[DONE]",
                    ),
                    2,
                )
                .build(),
        )
        val adapter = adapter(OpenAiChatCompatibilityMode.DEEPSEEK)

        val events = adapter.generate(
            profile(server),
            request(
                stream = true,
                parameters = GenerationParameters(
                    temperature = 0.7,
                    maxOutputTokens = 2_048,
                    reasoningEffort = ReasoningEffort.MEDIUM,
                ),
                schema = ProviderJsonSchema.from(
                    """{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}""",
                ),
            ),
        ).toList()

        assertEquals("第一章", text(events, structured = true))
        assertEquals(ProviderFinishReason.STOP, events.filterIsInstance<ProviderStreamEvent.Completed>().single().reason)
        val usage = events.filterIsInstance<ProviderStreamEvent.UsageUpdate>().single().usage
        assertEquals(120, usage.inputTokens)
        assertEquals(8, usage.outputTokens)
        assertEquals(90, usage.cachedInputTokens)
        assertEquals(4, usage.reasoningTokens)
        assertTrue(events.indexOfFirst { it is ProviderStreamEvent.UsageUpdate } < events.indexOfFirst { it is ProviderStreamEvent.Completed })

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.target)
        assertEquals("Bearer $TEST_CREDENTIAL", recorded.headers["Authorization"])
        val body = Json.parseToJsonElement(requireNotNull(recorded.body).utf8()).jsonObject
        assertEquals(2_048, body["max_tokens"]?.jsonPrimitive?.int)
        assertFalse("max_completion_tokens" in body)
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("json_object", body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertTrue(body["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.boolean == true)
        val messages = body["messages"]!!.jsonArray
        assertEquals(listOf("system", "user"), messages.roles())
        assertTrue(messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("STRUCTURED_OUTPUT"))
    }

    @Test
    fun `OpenAI non streaming uses strict json schema and current maximum token field`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"id":"chat-openai","choices":[{"index":0,"message":{"role":"assistant","content":"{\"title\":\"卷一\"}","refusal":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":6,"total_tokens":16,"prompt_tokens_details":{"cached_tokens":3},"completion_tokens_details":{"reasoning_tokens":2}}}""",
                )
                .build(),
        )
        val adapter = adapter(OpenAiChatCompatibilityMode.OPENAI)

        val events = adapter.generate(
            profile(server),
            request(
                stream = false,
                parameters = GenerationParameters(
                    maxOutputTokens = 4_096,
                    seed = 23,
                    reasoningEffort = ReasoningEffort.LOW,
                ),
                schema = ProviderJsonSchema.from("""{"type":"object","additionalProperties":false}"""),
            ),
        ).toList()

        assertEquals("{\"title\":\"卷一\"}", text(events, structured = true))
        assertEquals(ProviderFinishReason.STOP, events.filterIsInstance<ProviderStreamEvent.Completed>().single().reason)
        val body = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
        assertEquals(4_096, body["max_completion_tokens"]?.jsonPrimitive?.int)
        assertFalse("max_tokens" in body)
        assertEquals(23, body["seed"]?.jsonPrimitive?.int)
        val responseFormat = body["response_format"]!!.jsonObject
        assertEquals("json_schema", responseFormat["type"]!!.jsonPrimitive.content)
        assertTrue(responseFormat["json_schema"]!!.jsonObject["strict"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `minimal relay sends only baseline fields and accepts JSON fallback`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"choices":[{"message":{"content":"中转返回"},"finish_reason":"stop","index":0}]}""",
                )
                .build(),
        )
        val adapter = adapter(OpenAiChatCompatibilityMode.RELAY_MINIMAL)

        val events = adapter.generate(profile(server), request(stream = true)).toList()

        assertEquals("中转返回", text(events))
        assertTrue(events.last() is ProviderStreamEvent.Completed)
        val body = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
        assertEquals(setOf("model", "messages", "stream"), body.keys)
        val messages = body["messages"]!!.jsonArray
        assertEquals(listOf("user"), messages.roles())
        assertTrue(messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("STAGE_CONTRACT"))
    }

    @Test
    fun `OpenAI streamed refusal is emitted once after usage`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream; charset=utf-8")
                .body(
                    sse(
                        """{"id":"refusal","choices":[{"index":0,"delta":{"refusal":"Request "},"finish_reason":null}],"obfuscation":"ignored"}""",
                        """{"id":"refusal","choices":[{"index":0,"delta":{"refusal":"refused"},"finish_reason":"content_filter"}]}""",
                        """{"id":"refusal","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}""",
                        "[DONE]",
                    ),
                )
                .build(),
        )

        val events = adapter(OpenAiChatCompatibilityMode.OPENAI)
            .generate(profile(server), request(stream = true))
            .toList()

        assertTrue(events.none { it is ProviderStreamEvent.TextDelta })
        val refused = events.filterIsInstance<ProviderStreamEvent.Refused>().single()
        assertEquals(ProviderRefusalCategory.SAFETY, refused.category)
        assertEquals("Request refused", refused.userFacingMessage?.withValue { it })
        assertTrue(events.indexOfFirst { it is ProviderStreamEvent.UsageUpdate } < events.indexOfFirst { it is ProviderStreamEvent.Refused })
    }

    @Test
    fun `missing DONE never turns a billed partial stream into success`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(
                    sse(
                        """{"choices":[{"index":0,"delta":{"content":"半章"},"finish_reason":"stop"}]}""",
                    ),
                )
                .build(),
        )

        val events = adapter(OpenAiChatCompatibilityMode.DEEPSEEK)
            .generate(profile(server), request(stream = true))
            .toList()

        assertEquals("半章", text(events))
        assertEquals(StandardErrorCode.STREAM_INTERRUPTED, events.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertTrue(events.none { it is ProviderStreamEvent.Completed })
    }

    @Test
    fun `HTTP errors map without exposing provider messages`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "7")
                .addHeader("Content-Type", "application/json")
                .body("""{"error":{"code":"rate_limit_exceeded","message":"$ERROR_CANARY"}}""")
                .build(),
        )

        val events = adapter(OpenAiChatCompatibilityMode.OPENAI)
            .generate(profile(server), request(stream = false))
            .toList()

        val failure = events.filterIsInstance<ProviderStreamEvent.Failed>().single()
        assertEquals(StandardErrorCode.RATE_LIMITED, failure.code)
        assertEquals(429, failure.httpStatus)
        assertEquals(7_000, failure.retryAfterMillis)
        assertEquals(FailureRequestState.PROVIDER_REJECTED, failure.requestState)
        assertFalse(events.toString().contains(ERROR_CANARY))
    }

    @Test
    fun `HTTP date Retry After is normalized by the adapter`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder()
                .code(503)
                .addHeader("Retry-After", "Fri, 15 Jan 2027 08:02:00 GMT")
                .body("{}")
                .build(),
        )

        val failure = adapter(OpenAiChatCompatibilityMode.OPENAI)
            .generate(profile(server), request(stream = false))
            .toList()
            .filterIsInstance<ProviderStreamEvent.Failed>()
            .single()

        assertEquals(StandardErrorCode.SERVER_OVERLOADED, failure.code)
        assertEquals(120_000, failure.retryAfterMillis)
        assertEquals(FailureRequestState.PROVIDER_REJECTED, failure.requestState)
    }

    @Test
    fun `DeepSeek insufficient balance maps from status even with non JSON body`() = runBlocking {
        val server = server()
        server.enqueue(MockResponse.Builder().code(402).body("plain relay error").build())

        val failure = adapter(OpenAiChatCompatibilityMode.DEEPSEEK)
            .generate(profile(server), request(stream = false))
            .toList()
            .filterIsInstance<ProviderStreamEvent.Failed>()
            .single()

        assertEquals(StandardErrorCode.QUOTA_EXHAUSTED, failure.code)
        assertEquals(402, failure.httpStatus)
    }

    @Test
    fun `model list and connection test use GET models without paid generation`() = runBlocking {
        val server = server()
        val response = """{"object":"list","data":[{"id":"model-a","object":"model","owned_by":"relay"},{"id":"model-b","object":"model","owned_by":"relay"},{"id":"model-a","object":"model","owned_by":"relay"}]}"""
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(response).build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(response).build())
        val adapter = adapter(OpenAiChatCompatibilityMode.RELAY_MINIMAL)

        val models = adapter.listModels(profile(server)) as ModelListResult.Success
        val connection = adapter.testConnection(profile(server)) as ConnectionTestResult.Success

        assertEquals(2, models.models.size)
        assertFalse(connection.minimalGenerationVerified)
        assertFalse(connection.usageObserved)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("/v1/models", server.takeRequest().target)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `bounded verifier composes model discovery and one minimal OpenAI chat generation`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"object":"list","data":[{"id":"test-model","object":"model"}]}""",
            ).build(),
        )
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    """{"id":"probe","choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":null}]}""",
                    """{"id":"probe","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    """{"id":"probe","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":1,"total_tokens":13}}""",
                    "[DONE]",
                ),
            ).build(),
        )
        val adapter = adapter(OpenAiChatCompatibilityMode.OPENAI)
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
                selectedModelId = ProviderModelId.from("test-model"),
                verifyMinimalGeneration = true,
                minimalGenerationCostAcknowledged = true,
            ),
        )

        val report = (result as ConnectionVerificationResult.Completed).report
        assertTrue(report.modelListVerified)
        assertTrue(report.minimalGenerationVerified)
        assertTrue(report.usageObserved)
        val modelsRequest = server.takeRequest()
        val probeRequest = server.takeRequest()
        assertEquals("GET", modelsRequest.method)
        assertEquals("/v1/models", modelsRequest.target)
        assertEquals("POST", probeRequest.method)
        assertEquals("/v1/chat/completions", probeRequest.target)
        val body = Json.parseToJsonElement(requireNotNull(probeRequest.body).utf8()).jsonObject
        assertEquals(16, body["max_completion_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertFalse(body.toString().contains("PRIVATE_NOVEL"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `invalid schema and unknown relay options fail before network`() = runBlocking {
        val server = server()
        val invalidSchemaEvents = adapter(OpenAiChatCompatibilityMode.OPENAI).generate(
            profile(server),
            request(stream = false, schema = ProviderJsonSchema.from("{not-json}")),
        ).toList()
        val relayOptionEvents = adapter(OpenAiChatCompatibilityMode.RELAY_MINIMAL).generate(
            profile(server),
            request(stream = true, parameters = GenerationParameters(maxOutputTokens = 10)),
        ).toList()

        assertEquals(StandardErrorCode.PROTOCOL_MISMATCH, invalidSchemaEvents.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertEquals(StandardErrorCode.PROTOCOL_MISMATCH, relayOptionEvents.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertEquals(0, server.requestCount)
        assertEquals(null, server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    private fun adapter(mode: OpenAiChatCompatibilityMode): OpenAiChatAdapter = OpenAiChatAdapter(
        transport = SecureProviderHttpTransport(FakeSecretSource()),
        compatibilityResolver = FixedOpenAiChatCompatibilityResolver(mode),
        clock = { 1_800_000_000_000 },
    )

    private fun profile(server: MockWebServer): ProviderConnectionProfile = ProviderConnectionProfile.create(
        connectionId = "connection-openai-chat-023",
        protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
        baseUrl = server.url("/v1").toString(),
        primarySecretRefId = TEST_SECRET_REF,
        explicitLocalCleartext = true,
    )

    private fun request(
        stream: Boolean,
        parameters: GenerationParameters = GenerationParameters(),
        schema: ProviderJsonSchema? = null,
    ): GenerationRequest = GenerationRequest(
        requestId = "request-openai-chat-023",
        generationId = "generation-023",
        stageId = "stage-023",
        attemptId = "attempt-023",
        modelId = ProviderModelId.from("test-model"),
        prompt = ProviderPrompt(
            listOf(
                PromptPart(
                    PromptLayer.STAGE_CONTRACT,
                    SensitiveProviderText.from("写出完整章节，保留引号\"、换行\n和中文。"),
                ),
                PromptPart(PromptLayer.USER_REQUEST, SensitiveProviderText.from("开始写第一章。")),
            ),
        ),
        parameters = parameters,
        structuredOutputSchema = schema,
        stream = stream,
        timeouts = ProviderTimeoutPolicy(
            connectMillis = 1_000,
            firstByteMillis = 5_000,
            streamIdleMillis = 2_000,
            totalStageMillis = 10_000,
        ),
        idempotencyKey = null,
    )

    private fun server(): MockWebServer = MockWebServer().also {
        it.start()
        servers += it
    }

    private fun text(events: List<ProviderStreamEvent>, structured: Boolean = false): String =
        if (structured) {
            events.filterIsInstance<ProviderStreamEvent.StructuredDelta>()
                .joinToString("") { it.fragment.withValue { value -> value } }
        } else {
            events.filterIsInstance<ProviderStreamEvent.TextDelta>()
                .joinToString("") { it.text.withValue { value -> value } }
        }

    private fun sse(vararg data: String): String = data.joinToString(separator = "") { "data: $it\n\n" }

    private fun JsonArray.roles(): List<String> = map {
        it.jsonObject["role"]!!.jsonPrimitive.content
    }

    private class FakeSecretSource : ProviderSecretMaterialSource {
        override fun <T> withSecret(
            secretRefId: String,
            purpose: ProviderSecretPurpose,
            now: Long,
            block: (ByteArray) -> T,
        ): T {
            require(secretRefId == TEST_SECRET_REF)
            val value = TEST_CREDENTIAL.toByteArray()
            return try {
                block(value)
            } finally {
                value.fill(0)
            }
        }
    }

    private companion object {
        const val TEST_SECRET_REF = "123e4567-e89b-42d3-a456-426614174023"
        const val TEST_CREDENTIAL = "ZHIJUAN_UNIT_CREDENTIAL_023"
        const val ERROR_CANARY = "ZHIJUAN_REMOTE_ERROR_CANARY_023"
    }
}
