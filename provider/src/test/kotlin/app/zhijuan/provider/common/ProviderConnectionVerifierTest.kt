package app.zhijuan.provider.common

import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderConnectionVerifierTest {
    @Test
    fun `default verification lists models without a paid generation`() = runBlocking {
        val fixture = fixture()

        val result = fixture.verifier.verify(
            ConnectionVerificationRequest(
                profile = fixture.profile,
                selectedModelId = MODEL_ID,
            ),
        )

        val report = (result as ConnectionVerificationResult.Completed).report
        assertTrue(report.modelListVerified)
        assertTrue(report.connectionVerified)
        assertTrue(report.canSave)
        assertEquals(SelectedModelVerification.LISTED, report.selectedModel?.verification)
        assertInstanceOf(MinimalGenerationProbeResult.NotRequested::class.java, report.minimalGeneration)
        assertEquals(1, fixture.adapter.listCalls)
        assertEquals(0, fixture.adapter.capabilityCalls)
        assertEquals(0, fixture.adapter.generationCalls)
    }

    @Test
    fun `wrong credential is reported exactly once and never probes generation`() = runBlocking {
        val fixture = fixture(
            listResult = ModelListResult.Failure(
                ProviderCallFailure(
                    code = StandardErrorCode.AUTH_FAILED,
                    httpStatus = 401,
                    requestState = FailureRequestState.PROVIDER_REJECTED,
                ),
            ),
        )

        val result = fixture.verifier.verify(ConnectionVerificationRequest(fixture.profile))

        val failure = (result as ConnectionVerificationResult.Failure).failure
        assertEquals(StandardErrorCode.AUTH_FAILED, failure.code)
        assertEquals(1, fixture.adapter.listCalls)
        assertEquals(0, fixture.adapter.generationCalls)
    }

    @Test
    fun `unavailable model endpoint accepts a manual model as explicitly unverified`() = runBlocking {
        val fixture = fixture(listResult = unsupportedModelList())

        val result = fixture.verifier.verify(
            ConnectionVerificationRequest(
                profile = fixture.profile,
                selectedModelId = MANUAL_MODEL_ID,
            ),
        )

        val report = (result as ConnectionVerificationResult.Completed).report
        assertInstanceOf(ConnectionModelList.Unavailable::class.java, report.modelList)
        assertEquals(SelectedModelVerification.UNVERIFIED_MANUAL, report.selectedModel?.verification)
        assertFalse(report.connectionVerified)
        assertTrue(report.canSave)
        assertEquals(0, fixture.adapter.generationCalls)
    }

    @Test
    fun `unavailable model endpoint requests manual entry when none was supplied`() = runBlocking {
        val fixture = fixture(listResult = unsupportedModelList())

        val result = fixture.verifier.verify(ConnectionVerificationRequest(fixture.profile))

        val report = (result as ConnectionVerificationResult.Completed).report
        assertNull(report.selectedModel)
        assertFalse(report.connectionVerified)
        assertFalse(report.canSave)
    }

    @Test
    fun `manual fallback requires an actual provider response`() = runBlocking {
        val fixture = fixture(
            listResult = ModelListResult.Failure(
                ProviderCallFailure(
                    code = StandardErrorCode.PROTOCOL_MISMATCH,
                    requestState = FailureRequestState.RESULT_UNKNOWN,
                ),
            ),
        )

        val result = fixture.verifier.verify(
            ConnectionVerificationRequest(fixture.profile, selectedModelId = MANUAL_MODEL_ID),
        )

        assertEquals(
            StandardErrorCode.PROTOCOL_MISMATCH,
            (result as ConnectionVerificationResult.Failure).failure.code,
        )
    }

    @Test
    fun `a selected model missing from a successful list fails before generation`() = runBlocking {
        val fixture = fixture()

        val result = fixture.verifier.verify(
            ConnectionVerificationRequest(fixture.profile, selectedModelId = MANUAL_MODEL_ID),
        )

        val failure = (result as ConnectionVerificationResult.Failure).failure
        assertEquals(StandardErrorCode.MODEL_NOT_FOUND, failure.code)
        assertEquals(FailureRequestState.NOT_SENT, failure.requestState)
        assertEquals(0, fixture.adapter.generationCalls)
    }

    @Test
    fun `minimal generation cannot be requested without model and cost acknowledgement`() {
        val profile = profile()
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionVerificationRequest(
                profile = profile,
                verifyMinimalGeneration = true,
                minimalGenerationCostAcknowledged = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionVerificationRequest(
                profile = profile,
                selectedModelId = MODEL_ID,
                verifyMinimalGeneration = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionVerificationRequest(
                profile = profile,
                minimalGenerationCostAcknowledged = true,
            )
        }
    }

    @Test
    fun `acknowledged probe sends one fixed capped request and stores evidence`() = runBlocking {
        val fixture = fixture(
            generationEvents = successEvents(includeUsage = true),
        )

        val result = fixture.verifier.verify(deepRequest(fixture.profile))

        val report = (result as ConnectionVerificationResult.Completed).report
        val verified = report.minimalGeneration as MinimalGenerationProbeResult.Verified
        assertTrue(report.minimalGenerationVerified)
        assertTrue(report.usageObserved)
        assertEquals(SelectedModelVerification.MINIMAL_GENERATION, report.selectedModel?.verification)
        assertEquals(1, fixture.adapter.generationCalls)

        val request = requireNotNull(fixture.adapter.capturedRequest)
        assertTrue(request.stream)
        assertEquals(16, request.parameters.maxOutputTokens)
        assertNull(request.parameters.temperature)
        assertNull(request.parameters.topP)
        assertNull(request.parameters.seed)
        assertNull(request.parameters.reasoningEffort)
        assertNull(request.structuredOutputSchema)
        assertNull(request.idempotencyKey)
        request.prompt.withParts { parts ->
            assertEquals(1, parts.size)
            assertEquals(PromptLayer.STAGE_CONTRACT, parts.single().layer)
            parts.single().content.withValue { content ->
                assertEquals(
                    "Connection verification only. Reply with exactly OK and no other text.",
                    content,
                )
                assertFalse(content.contains(NOVEL_CANARY))
            }
        }

        assertEquals(CapabilitySource.PROBED, verified.evidence.source)
        assertEquals(CapabilitySupport.SUPPORTED, verified.evidence.streaming)
        assertEquals(CapabilitySupport.SUPPORTED, verified.evidence.usageInStream)
        assertEquals(CapabilitySupport.SUPPORTED, verified.evidence.maxOutputTokensParameter)
        val resolved = fixture.capabilityRegistry.resolveDetailed(
            profile = fixture.profile,
            modelId = MODEL_ID,
            adapterVersion = fixture.adapter.adapterVersion,
        )
        assertTrue(CapabilitySource.PROBED in resolved.appliedSources)
        assertEquals(CapabilitySupport.SUPPORTED, resolved.snapshot.maxOutputTokensParameter)
    }

    @Test
    fun `missing usage remains inconclusive while generation is verified`() = runBlocking {
        val fixture = fixture(generationEvents = successEvents(includeUsage = false))

        val result = fixture.verifier.verify(deepRequest(fixture.profile))

        val report = (result as ConnectionVerificationResult.Completed).report
        val verified = report.minimalGeneration as MinimalGenerationProbeResult.Verified
        assertFalse(report.usageObserved)
        assertEquals(CapabilitySupport.UNKNOWN, verified.evidence.usageInStream)
    }

    @Test
    fun `probe is skipped when a hard output cap cannot be sent`() = runBlocking {
        val fixture = fixture(capabilities = capabilities(maxOutputSupport = CapabilitySupport.UNKNOWN))

        val result = fixture.verifier.verify(deepRequest(fixture.profile))

        val report = (result as ConnectionVerificationResult.Completed).report
        val unavailable = report.minimalGeneration as MinimalGenerationProbeResult.NotSafelyAvailable
        assertEquals(
            GenerationProbeUnavailableReason.HARD_OUTPUT_LIMIT_NOT_SUPPORTED,
            unavailable.reason,
        )
        assertEquals(SelectedModelVerification.LISTED, report.selectedModel?.verification)
        assertTrue(report.canSave)
        assertEquals(0, fixture.adapter.generationCalls)
    }

    @Test
    fun `generation failure is not retried and does not erase a verified model list`() = runBlocking {
        val fixture = fixture(
            generationEvents = flowOf(
                ProviderStreamEvent.Failed(
                    code = StandardErrorCode.AUTH_FAILED,
                    httpStatus = 401,
                    requestState = FailureRequestState.PROVIDER_REJECTED,
                ),
            ),
        )

        val result = fixture.verifier.verify(deepRequest(fixture.profile))

        val report = (result as ConnectionVerificationResult.Completed).report
        val failure = (report.minimalGeneration as MinimalGenerationProbeResult.Failed).failure
        assertEquals(StandardErrorCode.AUTH_FAILED, failure.code)
        assertEquals(SelectedModelVerification.LISTED, report.selectedModel?.verification)
        assertTrue(report.connectionVerified)
        assertTrue(report.canSave)
        assertEquals(1, fixture.adapter.generationCalls)
    }

    @Test
    fun `a capped generation can verify a manual model when listing is unsupported`() = runBlocking {
        val fixture = fixture(
            listResult = unsupportedModelList(),
            generationEvents = successEvents(includeUsage = true),
        )

        val result = fixture.verifier.verify(
            ConnectionVerificationRequest(
                profile = fixture.profile,
                selectedModelId = MODEL_ID,
                verifyMinimalGeneration = true,
                minimalGenerationCostAcknowledged = true,
            ),
        )

        val report = (result as ConnectionVerificationResult.Completed).report
        assertFalse(report.modelListVerified)
        assertTrue(report.minimalGenerationVerified)
        assertTrue(report.connectionVerified)
        assertTrue(report.canSave)
        assertEquals(SelectedModelVerification.MINIMAL_GENERATION, report.selectedModel?.verification)
        assertEquals(1, fixture.adapter.generationCalls)
    }

    @Test
    fun `successful metadata hints are stored without inventing optional support`() = runBlocking {
        val fixture = fixture(
            listResult = ModelListResult.Success(
                models = listOf(
                    ProviderModelSummary(
                        id = MODEL_ID,
                        contextLimitHint = 128_000,
                        maxOutputTokensHint = 8_000,
                    ),
                ),
                fetchedAt = NOW,
            ),
        )

        fixture.verifier.verify(ConnectionVerificationRequest(fixture.profile))

        val resolution = fixture.capabilityRegistry.resolveDetailed(
            profile = fixture.profile,
            modelId = MODEL_ID,
            adapterVersion = fixture.adapter.adapterVersion,
        )
        assertEquals(128_000, resolution.snapshot.contextLimit)
        assertEquals(8_000, resolution.snapshot.maxOutputTokens)
        assertEquals(CapabilitySupport.UNKNOWN, resolution.snapshot.streaming)
        assertTrue(CapabilitySource.OFFICIAL_METADATA in resolution.appliedSources)
    }

    @Test
    fun `the entire connection check has a hard sixty second configurable deadline`() = runBlocking {
        val fixture = fixture(listDelayMillis = 100)

        val result = fixture.verifier.verify(
            ConnectionVerificationRequest(
                profile = fixture.profile,
                totalTimeoutMillis = 20,
            ),
        )

        assertEquals(20, (result as ConnectionVerificationResult.TimedOut).timeoutMillis)
        assertEquals(1, fixture.adapter.listCalls)
        assertEquals(0, fixture.adapter.generationCalls)
    }

    @Test
    fun `a paid probe receives only the time remaining after model discovery`() = runBlocking {
        val readings = ArrayDeque(listOf(0L, 55_000L))
        val fixture = fixture(monotonicClockMillis = { readings.removeFirst() })

        val result = fixture.verifier.verify(deepRequest(fixture.profile))

        assertInstanceOf(ConnectionVerificationResult.Completed::class.java, result)
        val timeouts = requireNotNull(fixture.adapter.capturedRequest).timeouts
        assertEquals(5_000, timeouts.connectMillis)
        assertEquals(5_000, timeouts.firstByteMillis)
        assertEquals(5_000, timeouts.streamIdleMillis)
        assertEquals(5_000, timeouts.totalStageMillis)
    }

    @Test
    fun `a stream without a terminal is never accepted as a successful probe`() = runBlocking {
        val fixture = fixture(
            generationEvents = flowOf(
                ProviderStreamEvent.Started(),
                ProviderStreamEvent.TextDelta(SensitiveProviderText.from("OK")),
            ),
        )

        val result = fixture.verifier.verify(deepRequest(fixture.profile))

        val report = (result as ConnectionVerificationResult.Completed).report
        val failure = (report.minimalGeneration as MinimalGenerationProbeResult.Failed).failure
        assertEquals(StandardErrorCode.STREAM_INTERRUPTED, failure.code)
        assertEquals(FailureRequestState.RESPONSE_STARTED, failure.requestState)
    }

    private fun fixture(
        listResult: ModelListResult = ModelListResult.Success(
            models = listOf(ProviderModelSummary(MODEL_ID)),
            fetchedAt = NOW,
        ),
        capabilities: ProviderCapabilitySnapshot = capabilities(),
        generationEvents: Flow<ProviderStreamEvent> = successEvents(includeUsage = true),
        listDelayMillis: Long = 0,
        monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    ): Fixture {
        val store = InMemoryProviderCapabilityStore()
        val capabilityRegistry = ProviderCapabilityRegistry(store, clock = { NOW })
        val adapter = FakeAdapter(
            listResult = listResult,
            capabilityResult = CapabilityResult.Success(capabilities),
            generationEvents = generationEvents,
            listDelayMillis = listDelayMillis,
        )
        return Fixture(
            profile = profile(),
            adapter = adapter,
            capabilityRegistry = capabilityRegistry,
            verifier = ProviderConnectionVerifier(
                adapters = ProviderAdapterRegistry(listOf(adapter)),
                capabilityRegistry = capabilityRegistry,
                clock = { NOW },
                monotonicClockMillis = monotonicClockMillis,
            ),
        )
    }

    private data class Fixture(
        val profile: ProviderConnectionProfile,
        val adapter: FakeAdapter,
        val capabilityRegistry: ProviderCapabilityRegistry,
        val verifier: ProviderConnectionVerifier,
    )

    private class FakeAdapter(
        private val listResult: ModelListResult,
        private val capabilityResult: CapabilityResult,
        private val generationEvents: Flow<ProviderStreamEvent>,
        private val listDelayMillis: Long,
    ) : ProviderAdapter {
        override val protocol = ProviderProtocol.OPENAI_CHAT_COMPAT
        override val adapterVersion = "connection-test-1"

        var listCalls = 0
            private set
        var capabilityCalls = 0
            private set
        var generationCalls = 0
            private set
        var capturedRequest: GenerationRequest? = null
            private set

        override suspend fun testConnection(profile: ProviderConnectionProfile): ConnectionTestResult =
            error("The verifier must use the richer model-list workflow.")

        override suspend fun listModels(profile: ProviderConnectionProfile): ModelListResult {
            listCalls += 1
            if (listDelayMillis > 0) delay(listDelayMillis)
            return listResult
        }

        override suspend fun getCapabilities(
            profile: ProviderConnectionProfile,
            modelId: ProviderModelId,
        ): CapabilityResult {
            capabilityCalls += 1
            return capabilityResult
        }

        override fun generate(
            profile: ProviderConnectionProfile,
            request: GenerationRequest,
        ): Flow<ProviderStreamEvent> {
            generationCalls += 1
            capturedRequest = request
            return generationEvents
        }

        override suspend fun cancel(
            profile: ProviderConnectionProfile,
            requestId: String,
        ): ProviderCancellationResult = ProviderCancellationResult.CANCELLED_LOCALLY
    }

    private companion object {
        const val NOW = 1_000L
        const val NOVEL_CANARY = "PRIVATE_NOVEL_MUST_NOT_ENTER_CONNECTION_PROBE"
        val MODEL_ID = ProviderModelId.from("fiction-model")
        val MANUAL_MODEL_ID = ProviderModelId.from("manual-model")

        fun profile() = ProviderConnectionProfile.create(
            connectionId = "connection-1",
            protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
            baseUrl = "https://provider.example/v1",
            primarySecretRefId = "123e4567-e89b-42d3-a456-426614174000",
        )

        fun unsupportedModelList(): ModelListResult = ModelListResult.Failure(
            ProviderCallFailure(
                code = StandardErrorCode.PROTOCOL_MISMATCH,
                httpStatus = 405,
                requestState = FailureRequestState.PROVIDER_REJECTED,
            ),
        )

        fun deepRequest(profile: ProviderConnectionProfile) = ConnectionVerificationRequest(
            profile = profile,
            selectedModelId = MODEL_ID,
            verifyMinimalGeneration = true,
            minimalGenerationCostAcknowledged = true,
        )

        fun capabilities(
            maxOutputSupport: CapabilitySupport = CapabilitySupport.SUPPORTED,
        ) = ProviderCapabilitySnapshot(
            protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
            modelId = MODEL_ID,
            streaming = CapabilitySupport.SUPPORTED,
            streamFormat = ProviderStreamFormat.SSE,
            structuredOutput = CapabilitySupport.UNKNOWN,
            usageInStream = CapabilitySupport.SUPPORTED,
            systemInstruction = CapabilitySupport.SUPPORTED,
            temperature = CapabilitySupport.UNKNOWN,
            topP = CapabilitySupport.UNKNOWN,
            maxOutputTokensParameter = maxOutputSupport,
            seed = CapabilitySupport.UNKNOWN,
            reasoningEffort = CapabilitySupport.UNKNOWN,
            idempotencyKey = CapabilitySupport.UNKNOWN,
            contextLimit = null,
            maxOutputTokens = null,
            tokenizerFamily = TokenizerFamily.UNKNOWN,
            source = CapabilitySource.BUILT_IN,
            verifiedAt = NOW,
            expiresAt = null,
            adapterVersion = "connection-test-1",
        )

        fun successEvents(includeUsage: Boolean): Flow<ProviderStreamEvent> = flowOf(
            *buildList {
                add(ProviderStreamEvent.Started())
                add(ProviderStreamEvent.TextDelta(SensitiveProviderText.from("OK")))
                if (includeUsage) {
                    add(
                        ProviderStreamEvent.UsageUpdate(
                            ProviderUsage(
                                inputTokens = 12,
                                outputTokens = 1,
                                cachedInputTokens = null,
                                cachedWriteTokens = null,
                                reasoningTokens = null,
                                totalTokens = 13,
                                quality = ProviderUsageQuality.PROVIDER_REPORTED,
                            ),
                        ),
                    )
                }
                add(ProviderStreamEvent.Completed(ProviderFinishReason.STOP))
            }.toTypedArray(),
        )
    }
}
