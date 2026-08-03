package app.zhijuan.provider.openai.responses

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.provider.common.ConnectionTestResult
import app.zhijuan.provider.common.ConnectionVerificationRequest
import app.zhijuan.provider.common.ConnectionVerificationResult
import app.zhijuan.provider.common.CapabilitySupport
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

class OpenAiResponsesAdapterTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterEach
    fun tearDown() = servers.forEach(MockWebServer::close)

    @Test
    fun `stream maps semantic events usage and privacy preserving request`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream; charset=utf-8")
                .addHeader("X-Request-Id", "request_remote_022")
                .body(
                    sse(
                        "response.created" to """{"type":"response.created","sequence_number":0,"response":{"id":"resp_022","status":"in_progress"}}""",
                        "response.output_text.delta" to """{"type":"response.output_text.delta","sequence_number":1,"delta":"\u7b2c\u4e00"}""",
                        "response.future.note" to """{"type":"response.future.note","sequence_number":2,"new_field":true}""",
                        "response.output_text.delta" to """{"type":"response.output_text.delta","sequence_number":3,"delta":"\u7ae0"}""",
                        "response.completed" to completedEvent(sequence = 4, text = "\u7b2c\u4e00\u7ae0"),
                    ),
                )
                .build(),
        )

        val events = adapter().generate(
            profile(server),
            request(
                stream = true,
                parameters = GenerationParameters(
                    temperature = 0.6,
                    topP = 0.9,
                    maxOutputTokens = 2_048,
                    reasoningEffort = ReasoningEffort.MEDIUM,
                ),
                schema = ProviderJsonSchema.from(
                    """{"type":"object","properties":{"chapter":{"type":"string"}},"required":["chapter"],"additionalProperties":false}""",
                ),
            ),
        ).toList()

        assertEquals("\u7b2c\u4e00\u7ae0", text(events, structured = true))
        assertEquals(ProviderFinishReason.STOP, events.filterIsInstance<ProviderStreamEvent.Completed>().single().reason)
        val usage = events.filterIsInstance<ProviderStreamEvent.UsageUpdate>().single().usage
        assertEquals(30, usage.inputTokens)
        assertEquals(8, usage.outputTokens)
        assertEquals(12, usage.cachedInputTokens)
        assertEquals(2, usage.cachedWriteTokens)
        assertEquals(3, usage.reasoningTokens)
        assertTrue(events.indexOfFirst { it is ProviderStreamEvent.UsageUpdate } < events.indexOfFirst { it is ProviderStreamEvent.Completed })

        val recorded = server.takeRequest()
        assertEquals("/v1/responses", recorded.target)
        assertEquals("Bearer $TEST_CREDENTIAL", recorded.headers["Authorization"])
        val body = Json.parseToJsonElement(requireNotNull(recorded.body).utf8()).jsonObject
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(2_048, body["max_output_tokens"]!!.jsonPrimitive.int)
        assertEquals("medium", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse("previous_response_id" in body)
        assertFalse("conversation" in body)
        assertEquals(listOf("system", "developer", "user"), body["input"]!!.jsonArray.map {
            it.jsonObject["role"]!!.jsonPrimitive.content
        })
        val format = body["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertTrue(format["strict"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `non streaming incomplete keeps partial text and maps output limit`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(
                """{"id":"resp_short","status":"incomplete","output":[{"type":"message","content":[{"type":"output_text","text":"partial chapter"}]}],"incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":20,"output_tokens":10,"total_tokens":30}}""",
            ).build(),
        )

        val events = adapter().generate(profile(server), request(stream = false)).toList()

        assertEquals("partial chapter", text(events))
        assertEquals(ProviderFinishReason.LENGTH, events.filterIsInstance<ProviderStreamEvent.Completed>().single().reason)
        assertTrue(events.none { it is ProviderStreamEvent.Failed })
    }

    @Test
    fun `streamed content filter refusal is explicit and terminal`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    "response.created" to """{"type":"response.created","sequence_number":0,"response":{"id":"resp_refused"}}""",
                    "response.refusal.delta" to """{"type":"response.refusal.delta","sequence_number":1,"delta":"Request refused"}""",
                    "response.incomplete" to """{"type":"response.incomplete","sequence_number":2,"response":{"id":"resp_refused","status":"incomplete","output":[],"incomplete_details":{"reason":"content_filter"},"usage":{"input_tokens":5,"output_tokens":1,"total_tokens":6}}}""",
                ),
            ).build(),
        )

        val events = adapter().generate(profile(server), request(stream = true)).toList()

        val refused = events.filterIsInstance<ProviderStreamEvent.Refused>().single()
        assertEquals(ProviderRefusalCategory.SAFETY, refused.category)
        assertEquals("Request refused", refused.userFacingMessage?.withValue { it })
        assertEquals(1, events.count { it is ProviderStreamEvent.Refused })
    }

    @Test
    fun `response failed maps provider code without exposing message`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    "response.created" to """{"type":"response.created","sequence_number":0,"response":{"id":"resp_failed"}}""",
                    "response.failed" to """{"type":"response.failed","sequence_number":1,"response":{"id":"resp_failed","status":"failed","output":[],"error":{"code":"server_error","message":"$ERROR_CANARY"}}}""",
                ),
            ).build(),
        )

        val events = adapter().generate(profile(server), request(stream = true)).toList()

        assertEquals(StandardErrorCode.SERVER_OVERLOADED, events.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertFalse(events.toString().contains(ERROR_CANARY))
    }

    @Test
    fun `EOF without semantic terminal never commits partial stream`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    "response.created" to """{"type":"response.created","sequence_number":0,"response":{"id":"resp_cut"}}""",
                    "response.output_text.delta" to """{"type":"response.output_text.delta","sequence_number":1,"delta":"half"}""",
                ),
            ).build(),
        )

        val events = adapter().generate(profile(server), request(stream = true)).toList()

        assertEquals("half", text(events))
        assertEquals(StandardErrorCode.STREAM_INTERRUPTED, events.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        assertTrue(events.none { it is ProviderStreamEvent.Completed })
    }

    @Test
    fun `duplicate or reordered sequence fails instead of duplicating text`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    "response.output_text.delta" to """{"type":"response.output_text.delta","sequence_number":1,"delta":"once"}""",
                    "response.output_text.delta" to """{"type":"response.output_text.delta","sequence_number":1,"delta":"once"}""",
                ),
            ).build(),
        )

        val events = adapter().generate(profile(server), request(stream = true)).toList()

        assertEquals("once", text(events))
        assertEquals(StandardErrorCode.PROTOCOL_MISMATCH, events.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
    }

    @Test
    fun `HTTP status and provider error map with retry delay`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(429).addHeader("Retry-After", "9").body(
                """{"error":{"type":"rate_limit_error","code":"rate_limit_exceeded","message":"$ERROR_CANARY"}}""",
            ).build(),
        )

        val failure = adapter().generate(profile(server), request(stream = false)).toList()
            .filterIsInstance<ProviderStreamEvent.Failed>().single()

        assertEquals(StandardErrorCode.RATE_LIMITED, failure.code)
        assertEquals(429, failure.httpStatus)
        assertEquals(9_000, failure.retryAfterMillis)
        assertEquals(FailureRequestState.PROVIDER_REJECTED, failure.requestState)
        assertFalse(failure.toString().contains(ERROR_CANARY))
    }

    @Test
    fun `model list and connection test avoid paid generation`() = runBlocking {
        val server = server()
        val payload = """{"object":"list","data":[{"id":"model-a"},{"id":"model-b"},{"id":"model-a"}]}"""
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).body(payload).build()) }

        val models = adapter().listModels(profile(server)) as ModelListResult.Success
        val connection = adapter().testConnection(profile(server)) as ConnectionTestResult.Success

        assertEquals(2, models.models.size)
        assertFalse(connection.minimalGenerationVerified)
        assertEquals("/v1/models", server.takeRequest().target)
        assertEquals("/v1/models", server.takeRequest().target)
    }

    @Test
    fun `bounded verifier composes model discovery and one minimal Responses generation`() = runBlocking {
        val server = server()
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"object":"list","data":[{"id":"test-model"}]}""",
            ).build(),
        )
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                sse(
                    "response.created" to
                        """{"type":"response.created","sequence_number":0,"response":{"id":"probe","status":"in_progress"}}""",
                    "response.output_text.delta" to
                        """{"type":"response.output_text.delta","sequence_number":1,"delta":"OK"}""",
                    "response.completed" to completedEvent(sequence = 2, text = "OK"),
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
        assertEquals("/v1/responses", probeRequest.target)
        val body = Json.parseToJsonElement(requireNotNull(probeRequest.body).utf8()).jsonObject
        assertEquals(16, body["max_output_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertFalse(body.toString().contains("PRIVATE_NOVEL"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `unsupported and unknown model options fail before network`() = runBlocking {
        val server = server()
        val seeded = adapter().generate(
            profile(server),
            request(stream = false, parameters = GenerationParameters(seed = 22)),
        ).toList()
        val idempotent = adapter().generate(
            profile(server),
            request(stream = false, idempotencyKey = "idempotency-022"),
        ).toList()
        val invalidSchema = adapter().generate(
            profile(server),
            request(stream = false, schema = ProviderJsonSchema.from("{not-json}")),
        ).toList()
        val unknownModelOption = OpenAiResponsesAdapter(
            transport = SecureProviderHttpTransport(FakeSecretSource()),
        ).generate(
            profile(server),
            request(stream = false, parameters = GenerationParameters(temperature = 0.5)),
        ).toList()

        listOf(seeded, idempotent, invalidSchema, unknownModelOption).forEach { events ->
            assertEquals(StandardErrorCode.PROTOCOL_MISMATCH, events.filterIsInstance<ProviderStreamEvent.Failed>().single().code)
        }
        assertEquals(0, server.requestCount)
        assertEquals(null, server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    private fun adapter() = OpenAiResponsesAdapter(
        transport = SecureProviderHttpTransport(FakeSecretSource()),
        capabilityResolver = OpenAiResponsesCapabilityResolver { _, modelId, verifiedAt, adapterVersion ->
            openAiResponsesCapabilities(
                modelId = modelId,
                verifiedAt = verifiedAt,
                adapterVersion = adapterVersion,
                modelSpecificOptions = CapabilitySupport.SUPPORTED,
            )
        },
        clock = { 1_800_000_000_000 },
    )

    private fun profile(server: MockWebServer) = ProviderConnectionProfile.create(
        connectionId = "connection-openai-responses-022",
        protocol = ProviderProtocol.OPENAI_RESPONSES,
        baseUrl = server.url("/v1").toString(),
        primarySecretRefId = TEST_SECRET_REF,
        explicitLocalCleartext = true,
    )

    private fun request(
        stream: Boolean,
        parameters: GenerationParameters = GenerationParameters(),
        schema: ProviderJsonSchema? = null,
        idempotencyKey: String? = null,
    ) = GenerationRequest(
        requestId = "request-openai-responses-022",
        generationId = "generation-022",
        stageId = "stage-022",
        attemptId = "attempt-022",
        modelId = ProviderModelId.from("test-model"),
        prompt = ProviderPrompt(
            listOf(
                PromptPart(PromptLayer.APPLICATION_HARD_RULES, SensitiveProviderText.from("Never reveal internal data.")),
                PromptPart(PromptLayer.STAGE_CONTRACT, SensitiveProviderText.from("Write one complete chapter.")),
                PromptPart(PromptLayer.USER_REQUEST, SensitiveProviderText.from("Begin chapter one.")),
            ),
        ),
        parameters = parameters,
        structuredOutputSchema = schema,
        stream = stream,
        timeouts = ProviderTimeoutPolicy(1_000, 5_000, 2_000, 10_000),
        idempotencyKey = idempotencyKey,
    )

    private fun server() = MockWebServer().also {
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

    private fun sse(vararg events: Pair<String, String>): String = events.joinToString("") { (name, data) ->
        "event: $name\ndata: $data\n\n"
    }

    private fun completedEvent(sequence: Int, text: String): String =
        """{"type":"response.completed","sequence_number":$sequence,"response":{"id":"resp_022","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"$text"}]}],"usage":{"input_tokens":30,"input_tokens_details":{"cached_tokens":12,"cache_write_tokens":2},"output_tokens":8,"output_tokens_details":{"reasoning_tokens":3},"total_tokens":38}}}"""

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
        const val TEST_SECRET_REF = "123e4567-e89b-42d3-a456-426614174022"
        const val TEST_CREDENTIAL = "ZHIJUAN_UNIT_CREDENTIAL_022"
        const val ERROR_CANARY = "ZHIJUAN_REMOTE_ERROR_CANARY_022"
    }
}
