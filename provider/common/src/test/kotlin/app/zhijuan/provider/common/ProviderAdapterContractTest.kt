package app.zhijuan.provider.common

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderAdapterContractTest {
    @Test
    fun fakeAdapterIsReplaceableThroughTheUnifiedInterface() = runBlocking {
        val adapter = FakeAdapter()
        val registry = ProviderAdapterRegistry(listOf(adapter))
        val profile = profile()
        val request = request()

        assertSame(adapter, registry.adapterFor(profile))
        assertTrue(adapter.testConnection(profile) is ConnectionTestResult.Success)
        assertEquals(
            listOf(MODEL_ID),
            (adapter.listModels(profile) as ModelListResult.Success).models.map(ProviderModelSummary::id),
        )
        assertEquals(
            CapabilitySupport.SUPPORTED,
            (adapter.getCapabilities(profile, MODEL_ID) as CapabilityResult.Success)
                .snapshot
                .structuredOutput,
        )
        val events = adapter.generate(profile, request).toList()
        assertTrue(events.first() is ProviderStreamEvent.Started)
        assertTrue(events.last() is ProviderStreamEvent.Completed)
        assertEquals(
            NOVEL_CANARY,
            (events[1] as ProviderStreamEvent.TextDelta).text.withValue { it },
        )
        assertEquals(
            ProviderCancellationResult.CANCELLED_LOCALLY,
            adapter.cancel(profile, request.requestId),
        )
    }

    @Test
    fun requestEventsAndProfileToStringNeverExposeSensitivePayloads() {
        val profile = profile()
        val request = request()
        val delta = ProviderStreamEvent.TextDelta(SensitiveProviderText.from(NOVEL_CANARY))
        val refusal = ProviderStreamEvent.Refused(
            ProviderRefusalCategory.POLICY,
            SensitiveProviderText.from(REFUSAL_CANARY),
        )
        val started = ProviderStreamEvent.Started(ProviderRemoteRequestId.from(REMOTE_ID_CANARY))
        val model = ProviderModelId.from(MODEL_ID_CANARY)

        assertFalse(request.toString().contains(NOVEL_CANARY))
        assertFalse(request.toString().contains(SCHEMA_CANARY))
        assertFalse(request.prompt.toString().contains(NOVEL_CANARY))
        assertFalse(delta.toString().contains(NOVEL_CANARY))
        assertFalse(refusal.toString().contains(REFUSAL_CANARY))
        assertFalse(started.toString().contains(REMOTE_ID_CANARY))
        assertFalse(model.toString().contains(MODEL_ID_CANARY))
        assertFalse(profile.toString().contains("provider.example"))
        assertFalse(profile.toString().contains(PRIMARY_SECRET_REF))
        assertFalse(profile.toString().contains(HEADER_SECRET_REF))
    }

    @Test
    fun unknownCapabilitiesNeverAuthorizeOptionalRequestFields() {
        val capabilities = ProviderCapabilitySnapshot.conservativeUnknown(
            protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
            modelId = MODEL_ID,
            verifiedAt = 10,
            adapterVersion = "fake-1",
        )

        assertEquals(
            setOf(
                ProviderRequestField.STREAMING,
                ProviderRequestField.TEMPERATURE,
                ProviderRequestField.TOP_P,
                ProviderRequestField.MAX_OUTPUT_TOKENS,
                ProviderRequestField.SEED,
                ProviderRequestField.STRUCTURED_OUTPUT,
                ProviderRequestField.REASONING_EFFORT,
                ProviderRequestField.IDEMPOTENCY_KEY,
            ),
            request().unsupportedFields(profile(), capabilities),
        )
        assertTrue(capabilities.supportFor(ProviderRequestField.SEED) == CapabilitySupport.UNKNOWN)
        assertFalse(capabilities.maySend(ProviderRequestField.SEED))
    }

    @Test
    fun eventGateEmitsOneTerminalAndIgnoresOutOfOrderOrLateEvents() {
        val gate = ProviderEventGate()
        assertEquals(
            ProviderEventDecision.Ignore(IgnoredProviderEventReason.BEFORE_STARTED),
            gate.accept(ProviderStreamEvent.TextDelta(SensitiveProviderText.from("late"))),
        )
        val started = ProviderStreamEvent.Started()
        assertEquals(ProviderEventDecision.Emit(started), gate.accept(started))
        assertEquals(
            ProviderEventDecision.Ignore(IgnoredProviderEventReason.DUPLICATE_STARTED),
            gate.accept(ProviderStreamEvent.Started()),
        )
        val completed = ProviderStreamEvent.Completed(ProviderFinishReason.STOP)
        assertEquals(ProviderEventDecision.Emit(completed), gate.accept(completed))
        assertEquals(
            ProviderEventDecision.Ignore(IgnoredProviderEventReason.AFTER_TERMINAL),
            gate.accept(ProviderStreamEvent.Heartbeat),
        )
        assertEquals(null, gate.terminalForUnexpectedEnd())

        val interrupted = ProviderEventGate()
        interrupted.accept(ProviderStreamEvent.Started())
        val unexpectedEnd = interrupted.terminalForUnexpectedEnd()
        assertEquals(
            StandardErrorCode.STREAM_INTERRUPTED,
            unexpectedEnd?.code,
        )
        assertEquals(FailureRequestState.RESPONSE_STARTED, unexpectedEnd?.requestState)
        assertEquals(null, interrupted.terminalForUnexpectedEnd())

        val codecFailure = ProviderEventGate()
        codecFailure.accept(ProviderStreamEvent.Started())
        val normalized = codecFailure.accept(
            ProviderStreamEvent.Failed(StandardErrorCode.STREAM_INTERRUPTED),
        ) as ProviderEventDecision.Emit
        assertEquals(
            FailureRequestState.RESPONSE_STARTED,
            (normalized.event as ProviderStreamEvent.Failed).requestState,
        )
    }

    @Test
    fun connectionProfileRejectsCredentialUrlsQueriesAndRemoteCleartext() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConnectionProfile.create(
                connectionId = "connection-1",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
                baseUrl = "https://user:password@provider.example/v1",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConnectionProfile.create(
                connectionId = "connection-1",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
                baseUrl = "https://provider.example/v1?token=value",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConnectionProfile.create(
                connectionId = "connection-1",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
                baseUrl = "http://provider.example/v1",
                explicitLocalCleartext = true,
            )
        }
        assertEquals(
            ProviderProtocol.OLLAMA_NATIVE,
            ProviderConnectionProfile.create(
                connectionId = "local-ollama",
                protocol = ProviderProtocol.OLLAMA_NATIVE,
                baseUrl = "http://127.0.0.1:11434/",
                explicitLocalCleartext = true,
            ).protocol,
        )
    }

    @Test
    fun usageContractRejectsUnknownValuesAndImpossibleTotals() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderUsage(
                inputTokens = 1,
                outputTokens = null,
                cachedInputTokens = null,
                cachedWriteTokens = null,
                reasoningTokens = null,
                totalTokens = null,
                quality = ProviderUsageQuality.UNKNOWN,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderUsage(
                inputTokens = 10,
                outputTokens = 20,
                cachedInputTokens = 5,
                cachedWriteTokens = null,
                reasoningTokens = 4,
                totalTokens = 25,
                quality = ProviderUsageQuality.PROVIDER_REPORTED,
            )
        }
    }

    @Test
    fun capabilitySnapshotCannotBeReusedAcrossAnotherModelOrProtocol() {
        val request = request()
        val wrongModel = ProviderCapabilitySnapshot.conservativeUnknown(
            protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
            modelId = ProviderModelId.from("another-model"),
            verifiedAt = 1L,
            adapterVersion = "fake-1",
        )
        val wrongProtocol = ProviderCapabilitySnapshot.conservativeUnknown(
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            modelId = MODEL_ID,
            verifiedAt = 1L,
            adapterVersion = "fake-1",
        )

        assertThrows(IllegalArgumentException::class.java) {
            request.unsupportedFields(profile(), wrongModel)
        }
        assertThrows(IllegalArgumentException::class.java) {
            request.unsupportedFields(profile(), wrongProtocol)
        }
    }

    @Test
    fun sensitiveHeaderReferenceViewIsImmutable() {
        profile().withSensitiveHeaderSecretRefs { headers ->
            assertEquals(HEADER_SECRET_REF, headers["x-api-key"])
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (headers as MutableMap<String, String>)["x-api-key"] = PRIMARY_SECRET_REF
            }
        }
    }

    private fun profile() = ProviderConnectionProfile.create(
        connectionId = "connection-1",
        protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
        baseUrl = "https://provider.example/v1/",
        primarySecretRefId = PRIMARY_SECRET_REF,
        sensitiveHeaderSecretRefs = mapOf("X-Api-Key" to HEADER_SECRET_REF),
    )

    private fun request() = GenerationRequest(
        requestId = "request-1",
        generationId = "generation-1",
        stageId = "stage-1",
        attemptId = "attempt-1",
        modelId = MODEL_ID,
        prompt = ProviderPrompt(
            listOf(
                PromptPart(PromptLayer.STAGE_CONTRACT, SensitiveProviderText.from("write one chapter")),
                PromptPart(PromptLayer.USER_REQUEST, SensitiveProviderText.from(NOVEL_CANARY)),
            ),
        ),
        parameters = GenerationParameters(
            temperature = 0.8,
            topP = 0.95,
            maxOutputTokens = 2_000,
            seed = 42,
            reasoningEffort = ReasoningEffort.MEDIUM,
        ),
        structuredOutputSchema = ProviderJsonSchema.from(
            """{"type":"object","description":"$SCHEMA_CANARY"}""",
        ),
        stream = true,
        timeouts = ProviderTimeoutPolicy(
            connectMillis = 10_000,
            firstByteMillis = 60_000,
            streamIdleMillis = 30_000,
            totalStageMillis = 300_000,
        ),
        idempotencyKey = "idem-key-0001",
    )

    private class FakeAdapter : ProviderAdapter {
        override val protocol = ProviderProtocol.OPENAI_CHAT_COMPAT
        override val adapterVersion = "fake-1"

        override suspend fun testConnection(profile: ProviderConnectionProfile): ConnectionTestResult =
            ConnectionTestResult.Success(
                verifiedAt = 10,
                minimalGenerationVerified = true,
                usageObserved = true,
            )

        override suspend fun listModels(profile: ProviderConnectionProfile): ModelListResult =
            ModelListResult.Success(listOf(ProviderModelSummary(MODEL_ID)), fetchedAt = 10)

        override suspend fun getCapabilities(
            profile: ProviderConnectionProfile,
            modelId: ProviderModelId,
        ): CapabilityResult = CapabilityResult.Success(capabilities())

        override fun generate(
            profile: ProviderConnectionProfile,
            request: GenerationRequest,
        ): Flow<ProviderStreamEvent> = flowOf(
            ProviderStreamEvent.Started(ProviderRemoteRequestId.from("remote-1")),
            ProviderStreamEvent.TextDelta(SensitiveProviderText.from(NOVEL_CANARY)),
            ProviderStreamEvent.UsageUpdate(
                ProviderUsage(
                    inputTokens = 10,
                    outputTokens = 20,
                    cachedInputTokens = 0,
                    cachedWriteTokens = 0,
                    reasoningTokens = 0,
                    totalTokens = 30,
                    quality = ProviderUsageQuality.PROVIDER_REPORTED,
                ),
            ),
            ProviderStreamEvent.Completed(ProviderFinishReason.STOP),
        )

        override suspend fun cancel(
            profile: ProviderConnectionProfile,
            requestId: String,
        ): ProviderCancellationResult = ProviderCancellationResult.CANCELLED_LOCALLY

        private fun capabilities() = ProviderCapabilitySnapshot(
            protocol = protocol,
            modelId = MODEL_ID,
            streaming = CapabilitySupport.SUPPORTED,
            streamFormat = ProviderStreamFormat.SSE,
            structuredOutput = CapabilitySupport.SUPPORTED,
            usageInStream = CapabilitySupport.SUPPORTED,
            systemInstruction = CapabilitySupport.SUPPORTED,
            temperature = CapabilitySupport.SUPPORTED,
            topP = CapabilitySupport.SUPPORTED,
            maxOutputTokensParameter = CapabilitySupport.SUPPORTED,
            seed = CapabilitySupport.SUPPORTED,
            reasoningEffort = CapabilitySupport.SUPPORTED,
            idempotencyKey = CapabilitySupport.SUPPORTED,
            contextLimit = 128_000,
            maxOutputTokens = 16_000,
            tokenizerFamily = TokenizerFamily.UNKNOWN,
            source = CapabilitySource.PROBED,
            verifiedAt = 10,
            expiresAt = 20,
            adapterVersion = adapterVersion,
        )
    }

    private companion object {
        val MODEL_ID = ProviderModelId.from("fiction-model")
        const val PRIMARY_SECRET_REF = "123e4567-e89b-42d3-a456-426614174000"
        const val HEADER_SECRET_REF = "123e4567-e89b-42d3-a456-426614174001"
        const val NOVEL_CANARY = "ZHIJUAN_PROVIDER_NOVEL_CANARY_020"
        const val REFUSAL_CANARY = "ZHIJUAN_PROVIDER_REFUSAL_CANARY_020"
        const val SCHEMA_CANARY = "ZHIJUAN_PROVIDER_SCHEMA_CANARY_020"
        const val REMOTE_ID_CANARY = "ZHIJUAN_PROVIDER_REMOTE_ID_CANARY_020"
        const val MODEL_ID_CANARY = "ZHIJUAN_PROVIDER_MODEL_ID_CANARY_020"
    }
}
