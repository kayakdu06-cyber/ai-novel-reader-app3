package app.zhijuan.reader.connection

import android.content.Context
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.contract.CurrentConnectionGateway
import app.zhijuan.core.contract.CurrentConnectionSelection
import app.zhijuan.core.security.AndroidSecretStore
import app.zhijuan.core.security.SecretPurpose
import app.zhijuan.provider.common.ConnectionModelList
import app.zhijuan.provider.common.ConnectionVerificationRequest
import app.zhijuan.provider.common.ConnectionVerificationResult
import app.zhijuan.provider.common.MinimalGenerationProbeResult
import app.zhijuan.provider.common.ProviderAdapterRegistry
import app.zhijuan.provider.common.ProviderCapabilityRegistry
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderConnectionVerifier
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.SelectedModelVerification
import app.zhijuan.provider.openai.chat.OpenAiChatAdapter
import app.zhijuan.provider.openai.chat.OpenAiChatCompatibilityMode
import app.zhijuan.provider.openai.chat.OpenAiChatCompatibilityResolver
import app.zhijuan.provider.openai.chat.RegistryBackedOpenAiChatCapabilityResolver
import app.zhijuan.provider.transport.AndroidProviderSecretMaterialSource
import app.zhijuan.provider.transport.SecureProviderHttpTransport
import app.zhijuan.provider.capability.storage.RoomProviderCapabilityStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ConnectionServiceChoice {
    OPENAI,
    DEEPSEEK,
    RELAY,
}

data class ConnectionWizardInput(
    val service: ConnectionServiceChoice,
    val relayBaseUrl: String = "",
)

data class PendingConnectionSnapshot(
    val connectionId: String,
    val service: ConnectionServiceChoice,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val endpointHost: String,
    val secretLastFour: String,
)

sealed interface ConnectionWizardCheckResult {
    data class Ready(
        val pending: PendingConnectionSnapshot,
        val models: List<String>,
        val recommendedModel: String,
    ) : ConnectionWizardCheckResult

    data class ManualModelAllowed(
        val pending: PendingConnectionSnapshot,
    ) : ConnectionWizardCheckResult

    data class Failed(
        val error: StandardErrorCode,
        val pendingCredential: PendingConnectionSnapshot?,
    ) : ConnectionWizardCheckResult

    data class TimedOut(
        val pendingCredential: PendingConnectionSnapshot?,
    ) : ConnectionWizardCheckResult

    data class InvalidInput(val reason: ConnectionWizardInputError) : ConnectionWizardCheckResult
}

enum class ConnectionWizardInputError {
    API_KEY_REQUIRED,
    API_KEY_INVALID,
    BASE_URL_REQUIRED,
    BASE_URL_INVALID,
    NO_MODELS_RETURNED,
    MODEL_ID_INVALID,
    NO_PENDING_CONNECTION,
}

sealed interface FullConnectionCheckResult {
    data class Verified(val usageObserved: Boolean) : FullConnectionCheckResult
    data class Failed(val error: StandardErrorCode) : FullConnectionCheckResult
    data object TimedOut : FullConnectionCheckResult
    data class InvalidInput(val reason: ConnectionWizardInputError) : FullConnectionCheckResult
}

sealed interface ConnectionCommitResult {
    data class Saved(val connection: SavedConnectionSnapshot) : ConnectionCommitResult
    data class InvalidInput(val reason: ConnectionWizardInputError) : ConnectionCommitResult
    data object Failed : ConnectionCommitResult
}

sealed interface ConnectionMutationResult {
    data object Success : ConnectionMutationResult
    data object NotFound : ConnectionMutationResult
    data object InvalidInput : ConnectionMutationResult
    data object Failed : ConnectionMutationResult
}

interface ConnectionWizardActions {
    suspend fun check(
        input: ConnectionWizardInput,
        newApiKey: CharArray?,
    ): ConnectionWizardCheckResult

    suspend fun runFullCheck(modelId: String): FullConnectionCheckResult

    suspend fun commitPending(modelId: String): ConnectionCommitResult

    suspend fun discardPending()
}

interface ConnectionManagementActions {
    suspend fun listConnections(): List<SavedConnectionSnapshot>
    suspend fun selectCurrent(connectionId: String): ConnectionMutationResult
    suspend fun editConnection(
        connectionId: String,
        displayName: String,
        selectedModelId: String,
    ): ConnectionMutationResult
    suspend fun deleteConnection(connectionId: String): ConnectionMutationResult
}

interface ConnectionGatewayActions : ConnectionWizardActions, ConnectionManagementActions

@Singleton
class ConnectionWizardGateway @Inject constructor(
    @ApplicationContext context: Context,
) : ConnectionGatewayActions, CurrentConnectionGateway {
    private val applicationContext = context.applicationContext
    private val secretStore = AndroidSecretStore(applicationContext)
    private val pendingPreferences = applicationContext.getSharedPreferences(
        PENDING_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val mutex = Mutex()
    private val repository = PersistentConnectionRepository(applicationContext)
    private val verifier: ProviderConnectionVerifier by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createVerifier()
    }
    private var pending: PendingConnection? = null
    private var stalePendingChecked = false

    override suspend fun currentConnection(): CurrentConnectionSelection? =
        listConnections().firstOrNull { it.isCurrent }?.let {
            CurrentConnectionSelection(it.connectionId, it.selectedModelId)
        }

    private fun createVerifier(): ProviderConnectionVerifier {
        val capabilityRegistry = ProviderCapabilityRegistry(
            RoomProviderCapabilityStore(repository.database),
        )
        val transport = SecureProviderHttpTransport(AndroidProviderSecretMaterialSource(secretStore))
        val compatibilityResolver = OpenAiChatCompatibilityResolver { profile ->
            profile.withBaseUrl { baseUrl ->
                when (URI(baseUrl).host.lowercase()) {
                    "api.openai.com" -> OpenAiChatCompatibilityMode.OPENAI
                    "api.deepseek.com" -> OpenAiChatCompatibilityMode.DEEPSEEK
                    else -> OpenAiChatCompatibilityMode.RELAY_MINIMAL
                }
            }
        }
        return ProviderConnectionVerifier(
            adapters = ProviderAdapterRegistry(
                listOf(
                    OpenAiChatAdapter(
                        transport = transport,
                        compatibilityResolver = compatibilityResolver,
                        capabilityResolver = RegistryBackedOpenAiChatCapabilityResolver(capabilityRegistry),
                    ),
                ),
            ),
            capabilityRegistry = capabilityRegistry,
        )
    }

    override suspend fun check(
        input: ConnectionWizardInput,
        newApiKey: CharArray?,
    ): ConnectionWizardCheckResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            cleanPendingFromPreviousProcessLocked()
            val endpoint = endpointFor(input)
                ?: return@withLock ConnectionWizardCheckResult.InvalidInput(
                if (input.relayBaseUrl.isBlank()) {
                    ConnectionWizardInputError.BASE_URL_REQUIRED
                } else {
                    ConnectionWizardInputError.BASE_URL_INVALID
                },
                ).also { newApiKey?.fill('\u0000') }

        if (pending?.matches(input, endpoint) != true) {
            revokePendingLocked()
        }

        if (newApiKey != null) {
            if (newApiKey.size !in MINIMUM_API_KEY_CHARACTERS..MAXIMUM_API_KEY_CHARACTERS ||
                newApiKey.any { it.code !in PRINTABLE_ASCII_NO_SPACE }
            ) {
                newApiKey.fill('\u0000')
                return@withLock ConnectionWizardCheckResult.InvalidInput(
                    ConnectionWizardInputError.API_KEY_INVALID,
                )
            }
            revokePendingLocked()
            val bytes = ByteArray(newApiKey.size) { index -> newApiKey[index].code.toByte() }
            newApiKey.fill('\u0000')
            val descriptor = try {
                secretStore.createAndClear(SecretPurpose.API_KEY, bytes, now())
            } catch (_: Exception) {
                bytes.fill(0)
                return@withLock ConnectionWizardCheckResult.InvalidInput(
                    ConnectionWizardInputError.API_KEY_INVALID,
                )
            }
            val profile = try {
                ProviderConnectionProfile.create(
                    connectionId = "connection-${UUID.randomUUID()}",
                    protocol = protocolFor(input.service),
                    baseUrl = endpoint,
                    primarySecretRefId = descriptor.secretRefId,
                )
            } catch (_: IllegalArgumentException) {
                runCatching { secretStore.revoke(descriptor.secretRefId, now()) }
                return@withLock ConnectionWizardCheckResult.InvalidInput(
                    ConnectionWizardInputError.BASE_URL_INVALID,
                )
            }
            val candidate = PendingConnection(
                input = input,
                profile = profile,
                secretRefId = descriptor.secretRefId,
                secretLastFour = descriptor.lastFour,
            )
            if (!pendingPreferences.edit().putString(PENDING_SECRET_REF, descriptor.secretRefId).commit()) {
                runCatching { secretStore.revoke(descriptor.secretRefId, now()) }
                return@withLock ConnectionWizardCheckResult.Failed(
                    error = StandardErrorCode.CREDENTIAL_UNAVAILABLE,
                    pendingCredential = null,
                )
            }
            pending = candidate
        }

        val active = pending ?: return@withLock ConnectionWizardCheckResult.InvalidInput(
            ConnectionWizardInputError.API_KEY_REQUIRED,
        )
            when (
                val result = verifier.verify(
                    ConnectionVerificationRequest(profile = active.profile),
                )
            ) {
            is ConnectionVerificationResult.Completed -> when (val list = result.report.modelList) {
                is ConnectionModelList.Available -> {
                    val models = list.models.map { summary -> summary.id.withValue { it } }
                        .distinct()
                        .take(MAX_STORED_MODELS)
                    if (models.isEmpty()) {
                        ConnectionWizardCheckResult.InvalidInput(ConnectionWizardInputError.NO_MODELS_RETURNED)
                    } else {
                        active.recordModelList(models, now())
                        ConnectionWizardCheckResult.Ready(
                            pending = active.snapshot(),
                            models = models,
                            recommendedModel = recommendModel(active.input.service, models),
                        )
                    }
                }
                is ConnectionModelList.Unavailable -> {
                    active.recordManualFallback(now())
                    ConnectionWizardCheckResult.ManualModelAllowed(active.snapshot())
                }
            }
            is ConnectionVerificationResult.Failure -> ConnectionWizardCheckResult.Failed(
                error = result.failure.code,
                pendingCredential = active.snapshot(),
            )
            is ConnectionVerificationResult.TimedOut -> ConnectionWizardCheckResult.TimedOut(
                pendingCredential = active.snapshot(),
            )
            }
        }
    }

    override suspend fun runFullCheck(modelId: String): FullConnectionCheckResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            cleanPendingFromPreviousProcessLocked()
            val active = pending ?: return@withLock FullConnectionCheckResult.InvalidInput(
                ConnectionWizardInputError.NO_PENDING_CONNECTION,
            )
            val selectedModel = try {
                ProviderModelId.from(modelId.trim())
            } catch (_: IllegalArgumentException) {
                return@withLock FullConnectionCheckResult.InvalidInput(
                    ConnectionWizardInputError.MODEL_ID_INVALID,
                )
            }
            when (
                val result = verifier.verify(
                    ConnectionVerificationRequest(
                        profile = active.profile,
                        selectedModelId = selectedModel,
                        verifyMinimalGeneration = true,
                        minimalGenerationCostAcknowledged = true,
                    ),
                )
            ) {
            is ConnectionVerificationResult.Completed -> {
                val probe = result.report.minimalGeneration
                if (
                    probe is MinimalGenerationProbeResult.Verified &&
                    result.report.selectedModel?.verification == SelectedModelVerification.MINIMAL_GENERATION
                ) {
                    active.recordFullVerification(selectedModel.withValue { it }, now())
                    FullConnectionCheckResult.Verified(probe.usageObserved)
                } else {
                    val error = when (probe) {
                        is MinimalGenerationProbeResult.Failed -> probe.failure.code
                        is MinimalGenerationProbeResult.NotSafelyAvailable ->
                            probe.capabilityFailure?.code ?: StandardErrorCode.PROTOCOL_MISMATCH
                        MinimalGenerationProbeResult.NotRequested,
                        is MinimalGenerationProbeResult.Verified,
                        -> StandardErrorCode.UNKNOWN_RESULT
                    }
                    FullConnectionCheckResult.Failed(error)
                }
            }
            is ConnectionVerificationResult.Failure -> FullConnectionCheckResult.Failed(result.failure.code)
            is ConnectionVerificationResult.TimedOut -> FullConnectionCheckResult.TimedOut
            }
        }
    }

    override suspend fun commitPending(modelId: String): ConnectionCommitResult = withContext(
        Dispatchers.IO + NonCancellable,
    ) {
        mutex.withLock {
            cleanPendingFromPreviousProcessLocked()
            val active = pending ?: return@withLock ConnectionCommitResult.InvalidInput(
                ConnectionWizardInputError.NO_PENDING_CONNECTION,
            )
            val selected = try {
                ProviderModelId.from(modelId.trim()).withValue { it }
            } catch (_: IllegalArgumentException) {
                return@withLock ConnectionCommitResult.InvalidInput(ConnectionWizardInputError.MODEL_ID_INVALID)
            }
            if (!active.canCommit(selected)) {
                return@withLock ConnectionCommitResult.InvalidInput(ConnectionWizardInputError.MODEL_ID_INVALID)
            }
            val timestamp = now()
            val verification = when {
                active.fullVerifiedModelId == selected -> ConnectionModelVerification.MINIMAL_GENERATION
                active.availableModels.contains(selected) -> ConnectionModelVerification.DISCOVERED
                else -> ConnectionModelVerification.MANUAL_UNVERIFIED
            }
            val saved = try {
                repository.insertAndSelectCurrent(
                    PersistentConnectionDraft(
                        connectionId = active.profile.connectionId,
                        displayName = defaultDisplayName(active.input.service, active.snapshot().endpointHost),
                        service = active.input.service,
                        protocol = active.profile.protocol,
                        baseUrl = active.profile.withBaseUrl { it },
                        secretRefId = active.secretRefId,
                        secretLastFour = active.secretLastFour,
                        selectedModelId = selected,
                        availableModels = active.availableModels,
                        modelVerification = verification,
                        basicVerifiedAt = active.basicVerifiedAt ?: timestamp,
                        fullVerifiedAt = if (verification == ConnectionModelVerification.MINIMAL_GENERATION) {
                            active.fullVerifiedAt ?: timestamp
                        } else {
                            null
                        },
                        createdAt = timestamp,
                    ),
                )
            } catch (_: Exception) {
                return@withLock ConnectionCommitResult.Failed
            }
            pending = null
            pendingPreferences.edit().remove(PENDING_SECRET_REF).commit()
            ConnectionCommitResult.Saved(saved)
        }
    }

    override suspend fun listConnections(): List<SavedConnectionSnapshot> = withContext(Dispatchers.IO) {
        mutex.withLock {
            cleanPendingFromPreviousProcessLocked()
            repository.list()
        }
    }

    override suspend fun selectCurrent(connectionId: String): ConnectionMutationResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            cleanPendingFromPreviousProcessLocked()
            try {
                repository.selectCurrent(connectionId, now())
                ConnectionMutationResult.Success
            } catch (_: IllegalArgumentException) {
                ConnectionMutationResult.NotFound
            } catch (_: Exception) {
                ConnectionMutationResult.Failed
            }
        }
    }

    override suspend fun editConnection(
        connectionId: String,
        displayName: String,
        selectedModelId: String,
    ): ConnectionMutationResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            cleanPendingFromPreviousProcessLocked()
            try {
                repository.edit(connectionId, displayName, selectedModelId, now())
                ConnectionMutationResult.Success
            } catch (_: IllegalArgumentException) {
                ConnectionMutationResult.InvalidInput
            } catch (_: Exception) {
                ConnectionMutationResult.Failed
            }
        }
    }

    override suspend fun deleteConnection(connectionId: String): ConnectionMutationResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            cleanPendingFromPreviousProcessLocked()
            val deleted = try {
                repository.delete(connectionId, now())
            } catch (_: IllegalArgumentException) {
                return@withLock ConnectionMutationResult.NotFound
            } catch (_: Exception) {
                return@withLock ConnectionMutationResult.Failed
            }
            runCatching { secretStore.revoke(deleted.secretRefId, now()) }
            ConnectionMutationResult.Success
        }
    }

    override suspend fun discardPending() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cleanPendingFromPreviousProcessLocked()
                revokePendingLocked()
            }
        }
    }

    private fun endpointFor(input: ConnectionWizardInput): String? = when (input.service) {
        ConnectionServiceChoice.OPENAI -> OPENAI_BASE_URL
        ConnectionServiceChoice.DEEPSEEK -> DEEPSEEK_BASE_URL
        ConnectionServiceChoice.RELAY -> input.relayBaseUrl.trim().trimEnd('/').takeIf { candidate ->
            runCatching {
                ProviderConnectionProfile.create(
                    connectionId = "connection-validation",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
                    baseUrl = candidate,
                )
            }.isSuccess
        }
    }

    private fun protocolFor(service: ConnectionServiceChoice): ProviderProtocol = when (service) {
        ConnectionServiceChoice.OPENAI -> ProviderProtocol.OPENAI_CHAT_COMPAT
        ConnectionServiceChoice.DEEPSEEK,
        ConnectionServiceChoice.RELAY,
        -> ProviderProtocol.OPENAI_CHAT_COMPAT
    }

    private fun recommendModel(
        service: ConnectionServiceChoice,
        models: List<String>,
    ): String {
        val candidates = models.filterNot { model ->
            val lower = model.lowercase()
            EXCLUDED_MODEL_MARKERS.any(lower::contains)
        }.ifEmpty { models }
        val preferredMarkers = when (service) {
            ConnectionServiceChoice.OPENAI -> listOf("gpt-")
            ConnectionServiceChoice.DEEPSEEK -> listOf("deepseek-chat", "deepseek")
            ConnectionServiceChoice.RELAY -> listOf("chat", "instruct")
        }
        return preferredMarkers.firstNotNullOfOrNull { marker ->
            candidates.firstOrNull { it.lowercase().contains(marker) }
        } ?: candidates.first()
    }

    private fun defaultDisplayName(service: ConnectionServiceChoice, endpointHost: String): String = when (service) {
        ConnectionServiceChoice.OPENAI -> "OpenAI"
        ConnectionServiceChoice.DEEPSEEK -> "DeepSeek"
        ConnectionServiceChoice.RELAY -> endpointHost
    }

    private fun revokePendingLocked() {
        pending?.let { active ->
            runCatching { secretStore.revoke(active.secretRefId, now()) }
        }
        pending = null
        pendingPreferences.edit().remove(PENDING_SECRET_REF).commit()
    }

    private suspend fun cleanPendingFromPreviousProcessLocked() {
        if (stalePendingChecked) return
        val staleRef = pendingPreferences.getString(PENDING_SECRET_REF, null) ?: run {
            stalePendingChecked = true
            return
        }
        val committed = try {
            repository.referencesSecret(staleRef)
        } catch (_: Exception) {
            return
        }
        stalePendingChecked = true
        if (!committed) runCatching { secretStore.revoke(staleRef, now()) }
        pendingPreferences.edit().remove(PENDING_SECRET_REF).commit()
    }

    private fun now(): Long = System.currentTimeMillis().coerceAtLeast(0)

    private data class PendingConnection(
        val input: ConnectionWizardInput,
        val profile: ProviderConnectionProfile,
        val secretRefId: String,
        val secretLastFour: String,
    ) {
        var availableModels: List<String> = emptyList()
            private set
        var manualModelAllowed: Boolean = false
            private set
        var basicVerifiedAt: Long? = null
            private set
        var fullVerifiedModelId: String? = null
            private set
        var fullVerifiedAt: Long? = null
            private set

        fun recordModelList(models: List<String>, verifiedAt: Long) {
            availableModels = models.distinct().take(MAX_STORED_MODELS)
            manualModelAllowed = false
            basicVerifiedAt = verifiedAt
            fullVerifiedModelId = null
            fullVerifiedAt = null
        }

        fun recordManualFallback(verifiedAt: Long) {
            availableModels = emptyList()
            manualModelAllowed = true
            basicVerifiedAt = verifiedAt
            fullVerifiedModelId = null
            fullVerifiedAt = null
        }

        fun recordFullVerification(modelId: String, verifiedAt: Long) {
            fullVerifiedModelId = modelId
            fullVerifiedAt = verifiedAt
        }

        fun canCommit(modelId: String): Boolean =
            basicVerifiedAt != null && (modelId in availableModels || manualModelAllowed)

        fun matches(other: ConnectionWizardInput, endpoint: String): Boolean =
            input.service == other.service && profile.withBaseUrl { it } == endpoint

        fun snapshot(): PendingConnectionSnapshot {
            val endpoint = profile.withBaseUrl { it }
            return PendingConnectionSnapshot(
                connectionId = profile.connectionId,
                service = input.service,
                protocol = profile.protocol,
                baseUrl = endpoint,
                endpointHost = URI(endpoint).host,
                secretLastFour = secretLastFour,
            )
        }
    }

    private companion object {
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        const val MINIMUM_API_KEY_CHARACTERS = 8
        const val MAXIMUM_API_KEY_CHARACTERS = 16_384
        val PRINTABLE_ASCII_NO_SPACE = 0x21..0x7e
        val EXCLUDED_MODEL_MARKERS = listOf(
            "embedding",
            "moderation",
            "audio",
            "image",
            "realtime",
            "transcribe",
            "tts",
            "vision",
            "live",
        )
        const val PENDING_PREFERENCES = "pending-connection-wizard"
        const val PENDING_SECRET_REF = "pending-secret-ref"
        const val MAX_STORED_MODELS = 500
    }
}
