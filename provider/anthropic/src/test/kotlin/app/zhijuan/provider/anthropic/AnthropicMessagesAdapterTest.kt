package app.zhijuan.provider.anthropic

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnthropicMessagesAdapterTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterEach
    fun tearDown() = servers.forEach(MockWebServer::close)

    @Test
    fun `stream follows lifecycle ignores thinking and sends versioned private request`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .addHeader("request-id", "req_remote_024")
                .body(
                    sse(
                        "message_start" to """{"type":"message_start","message":{"id":"msg_024","type":"message","role":"assistant","content":[],"stop_reason":null,"usage":{"input_tokens":20,"cache_creation_input_tokens":3,"cache_read_input_tokens":7,"output_tokens":1}}}""",
                        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hidden reasoning"}}""",
                        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"hidden signature"}}""",
                        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
                        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"\u7b2c\u4e00"}}""",
                        "future_event" to """{"type":"future_event","value":true}""",
                        "ping" to """{"type":"ping"}""",
                        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"\u7ae0"}}""",
                        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
                        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":10,"output_tokens_details":{"thinking_tokens":4}}}""",
                        "message_stop" to """{"type":"message_stop"}""",
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
                    reasoningEffort = ReasoningEffort.MEDIUM,
                ),
                schema = ProviderJsonSchema.from(
                    """{"type":"object","properties":{"chapter":{"type":"string"}},"required":["chapter"]}""",
                ),
            ),
        ).toList()

        assertEquals("\u7b2c\u4e00\u7ae0", text(events, structured = true))
        assertFalse(events.toString().contains("hidden reasoning"))
        assertEquals(ProviderFinishReason.STOP, events.filterIsInstance<ProviderStreamEvent.Completed>().single().reason)
        val usage = events.filterIsInstance<ProviderStreamEvent.UsageUpdate>()
        assertEquals(2, usage.size)
        assertEquals(20, usage.first().usage.inputTokens)
        assertEquals(7, usage.first().usage.cachedInputTokens)
        assertEquals(3, usage.first().usage.cachedWriteTokens)
        assertEquals(10, usage.last().usage.outputTokens)
        assertEquals(4, usage.last().usage.reasoningTokens)

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.target)
        assertEquals(AnthropicMessagesAdapter.API_VERSION, recorded.headers["anthropic-version"])
        assertEquals(TEST_CREDENTIAL, recorded.headers["x-api-key"])
        val body = Json.parseToJsonElement(requireNotNull(recorded.body).utf8()).jsonObject
        assertEquals(2_048, body["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["system"]!!.jsonPrimitive.content.contains("STAGE_CONTRACT"))
        assertEquals("user", body["messages"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", body["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("thinking" in body)
    }

    @Test
    fun `non streaming max tokens keeps content and maps length`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(
                """{"id":"msg_short","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"hidden","signature":"sig"},{"type":"text","text":"partial"}],"stop_reason":"max_tokens","stop_sequence":null,"usage":{"input_tokens":8,"output_tokens":16}}""",
            ).build(),
        )

        val events = adapter().generate(profile(server), request(stream = false)).toList()

        assertEquals("partial", text(events))
        assertEquals(ProviderFinishReason.LENGTH, events.filterIsInstance<ProviderStreamEvent.Completed>().single().reason)
        assertFalse(events.toString().contains("hidden"))
    }

    @Test
    fun `stream refusal has bounded user facing explanation`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    "message_start" to """{"type":"message_start","message":{"id":"msg_refuse","type":"message","content":[],"usage":{"input_tokens":5,"output_tokens":1}}}""",
                    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"type":"refusal","category":"general_harms","explanation":"Request declined"}},"usage":{"output_tokens":2}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                ),
            ).build(),
        )

        val events = adapter().generate(profile(server), request(stream = true)).toList()

        val refused = events.filterIsInstance<ProviderStreamEvent.Refused>().single()
        assertEquals(ProviderRefusalCategory.POLICY, refused.category)
        assertEquals("Request declined", refused.userFacingMessage?.withValue { it })
    }

    @Test
    fun `context window stop and pause turn never become successful stop`() = runBlocking {
        val contextServer = server()
        contextServer.enqueue(
            MockResponse.Builder().code(200).body(
                """{"id":"msg_context","type":"message","role":"assistant","content":[],"stop_reason":"model_context_window_exceeded","usage":{"input_tokens":100,"output_tokens":1}}""",
            ).build(),
        )
        val pauseServer = server()
        pauseServer.enqueue(
            MockResponse.Builder().code(200).body(
                """{"id":"msg_pause","type":"message","role":"assistant","content":[],"stop_reason":"pause_turn","usage":{"input_tokens":10,"output_tokens":1}}""",
            ).build(),
        )

        val context = adapter().generate(profile(contextServer), request(stream = false)).toList()
        val pause = adapter().generate(profile(pauseServer), request(stream = false)).toList()

        assertEquals(StandardErrorCode.CONTEXT_TOO_LARGE, context.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertEquals(StandardErrorCode.UNKNOWN_RESULT, pause.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
    }

    @Test
    fun `mid stream overload error is terminal and message is not exposed`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    "message_start" to """{"type":"message_start","message":{"id":"msg_error","type":"message","content":[],"usage":{"input_tokens":5,"output_tokens":1}}}""",
                    "error" to """{"type":"error","error":{"type":"overloaded_error","message":"$ERROR_CANARY"}}""",
                ),
            ).build(),
        )

        val events = adapter().generate(profile(server), request(stream = true)).toList()

        assertEquals(StandardErrorCode.SERVER_OVERLOADED, events.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertFalse(events.toString().contains(ERROR_CANARY))
    }

    @Test
    fun `missing message stop and invalid block lifecycle fail closed`() = runBlocking {
        val cutServer = server()
        cutServer.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    "message_start" to """{"type":"message_start","message":{"id":"msg_cut","type":"message","content":[],"usage":{"input_tokens":5,"output_tokens":1}}}""",
                    "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                    "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"half"}}""",
                ),
            ).build(),
        )
        val invalidServer = server()
        invalidServer.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    "message_start" to """{"type":"message_start","message":{"id":"msg_invalid","type":"message","content":[],"usage":{"input_tokens":5,"output_tokens":1}}}""",
                    "content_block_delta" to """{"type":"content_block_delta","index":9,"delta":{"type":"text_delta","text":"bad"}}""",
                ),
            ).build(),
        )

        val cut = adapter().generate(profile(cutServer), request(stream = true)).toList()
        val invalid = adapter().generate(profile(invalidServer), request(stream = true)).toList()

        assertEquals("half", text(cut))
        assertEquals(StandardErrorCode.STREAM_INTERRUPTED, cut.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertEquals(StandardErrorCode.PROTOCOL_MISMATCH, invalid.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
    }

    @Test
    fun `HTTP 529 overload maps retry delay without message leakage`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(529).addHeader("Retry-After", "6").body(
                """{"type":"error","error":{"type":"overloaded_error","message":"$ERROR_CANARY"},"request_id":"req_hidden"}""",
            ).build(),
        )

        val failure = adapter().generate(profile(server), request(stream = false)).toList()
            .filterIsInstance<ProviderStreamEvent.Failed>().single()

        assertEquals(StandardErrorCode.SERVER_OVERLOADED, failure.code)
        assertEquals(529, failure.httpStatus)
        assertEquals(6_000, failure.retryAfterMillis)
        assertEquals(FailureRequestState.PROVIDER_REJECTED, failure.requestState)
        assertFalse(failure.toString().contains(ERROR_CANARY))
    }

    @Test
    fun `model list and connection test use versioned GET only`() = runBlocking {
        val server = server()
        val body = """{"data":[{"id":"model-a"},{"id":"model-b"},{"id":"model-a"}],"has_more":false}"""
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).body(body).build()) }

        val models = adapter().listModels(profile(server)) as ModelListResult.Success
        val connection = adapter().testConnection(profile(server)) as ConnectionTestResult.Success

        assertEquals(2, models.models.size)
        assertFalse(connection.minimalGenerationVerified)
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("/v1/models", request.target)
            assertEquals(AnthropicMessagesAdapter.API_VERSION, request.headers["anthropic-version"])
        }
    }

    @Test
    fun `bounded verifier composes model discovery and one minimal Anthropic generation`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"data":[{"id":"test-model"}],"has_more":false}""",
            ).build(),
        )
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    "message_start" to
                        """{"type":"message_start","message":{"id":"probe","type":"message","role":"assistant","content":[],"stop_reason":null,"usage":{"input_tokens":12,"output_tokens":0}}}""",
                    "content_block_start" to
                        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                    "content_block_delta" to
                        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"OK"}}""",
                    "content_block_stop" to
                        """{"type":"content_block_stop","index":0}""",
                    "message_delta" to
                        """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}""",
                    "message_stop" to """{"type":"message_stop"}""",
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
                selectedModelId = ProviderModelId.from("test-model"),
                verifyMinimalGeneration = true,
                minimalGenerationCostAcknowledged = true,
            ),
        )

        val report = (result as ConnectionVerificationResult.Completed).report
        assertTrue(report.modelListVerified)
        assertTrue(report.minimalGenerationVerified)
        assertTrue(report.usageObserved)
        assertEquals("/v1/models", server.takeRequest().target)
        val probeRequest = server.takeRequest()
        assertEquals("/v1/messages", probeRequest.target)
        val body = Json.parseToJsonElement(requireNotNull(probeRequest.body).utf8()).jsonObject
        assertEquals(16, body["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertFalse(body.toString().contains("PRIVATE_NOVEL"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `missing required and unknown model fields fail before network`() = runBlocking {
        val server = server()
        val missingMaximum = adapter().generate(
            profile(server),
            request(stream = false, parameters = GenerationParameters()),
        ).toList()
        val invalidTemperature = adapter().generate(
            profile(server),
            request(stream = false, parameters = GenerationParameters(maxOutputTokens = 100, temperature = 1.5)),
        ).toList()
        val unsupportedSeed = adapter().generate(
            profile(server),
            request(stream = false, parameters = GenerationParameters(maxOutputTokens = 100, seed = 24)),
        ).toList()
        val unknownStructured = AnthropicMessagesAdapter(
            SecureProviderHttpTransport(FakeSecretSource()),
        ).generate(
            profile(server),
            request(
                stream = false,
                schema = ProviderJsonSchema.from("""{"type":"object"}"""),
            ),
        ).toList()

        listOf(missingMaximum, invalidTemperature, unsupportedSeed, unknownStructured).forEach {
            assertEquals(StandardErrorCode.PROTOCOL_MISMATCH, it.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        }
        assertEquals(0, server.requestCount)
        assertEquals(null, server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    private fun adapter() = AnthropicMessagesAdapter(
        transport = SecureProviderHttpTransport(FakeSecretSource()),
        capabilityResolver = AnthropicCapabilityResolver { _, modelId, verifiedAt, version ->
            anthropicCapabilities(modelId, verifiedAt, version, CapabilitySupport.SUPPORTED)
        },
        clock = { 1_800_000_000_000 },
    )

    private fun profile(server: MockWebServer) = ProviderConnectionProfile.create(
        connectionId = "connection-anthropic-024",
        protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
        baseUrl = server.url("/v1").toString(),
        primarySecretRefId = TEST_SECRET_REF,
        explicitLocalCleartext = true,
    )

    private fun request(
        stream: Boolean,
        parameters: GenerationParameters = GenerationParameters(maxOutputTokens = 1_024),
        schema: ProviderJsonSchema? = null,
    ) = GenerationRequest(
        requestId = "request-anthropic-024",
        generationId = "generation-024",
        stageId = "stage-024",
        attemptId = "attempt-024",
        modelId = ProviderModelId.from("test-model"),
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

    private fun sse(vararg events: Pair<String, String>) = events.joinToString("") { (name, data) ->
        "event: $name\ndata: $data\n\n"
    }

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
        const val TEST_SECRET_REF = "123e4567-e89b-42d3-a456-426614174024"
        const val TEST_CREDENTIAL = "ZHIJUAN_ANTHROPIC_TEST_CREDENTIAL_024"
        const val ERROR_CANARY = "ZHIJUAN_ANTHROPIC_ERROR_CANARY_024"
    }
}
