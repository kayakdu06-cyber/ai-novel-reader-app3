package app.zhijuan.reader.connection

import android.content.Context
import app.zhijuan.core.database.EncryptedZhijuanDatabaseFactory
import app.zhijuan.core.database.connection.AcceptedDataDisclosureEvidence
import app.zhijuan.core.database.connection.ConnectionProfileEntity
import app.zhijuan.core.model.ExternalDataDestinationBindingV1
import app.zhijuan.core.database.ZHIJUAN_DATABASE_NAME
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderProtocol
import java.net.URI
import org.json.JSONArray

enum class ConnectionModelVerification {
    DISCOVERED,
    MANUAL_UNVERIFIED,
    MINIMAL_GENERATION,
}

data class SavedConnectionSnapshot(
    val connectionId: String,
    val displayName: String,
    val service: ConnectionServiceChoice,
    val protocol: ProviderProtocol,
    val protocolId: String = protocol.name,
    val baseUrl: String,
    val endpointHost: String,
    val secretLastFour: String,
    val selectedModelId: String,
    val availableModels: List<String>,
    val modelVerification: ConnectionModelVerification,
    val isCurrent: Boolean,
    val updatedAt: Long,
)

internal data class PersistentConnectionDraft(
    val connectionId: String,
    val displayName: String,
    val service: ConnectionServiceChoice,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val secretRefId: String,
    val secretLastFour: String,
    val selectedModelId: String,
    val availableModels: List<String>,
    val modelVerification: ConnectionModelVerification,
    val basicVerifiedAt: Long,
    val fullVerifiedAt: Long?,
    val createdAt: Long,
)

internal class PersistentConnectionRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val databaseHandle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EncryptedZhijuanDatabaseFactory(applicationContext).open(ZHIJUAN_DATABASE_NAME)
    }
    private val dao get() = databaseHandle.database.connectionDao()

    val database get() = databaseHandle.database

    suspend fun insertAndSelectCurrent(draft: PersistentConnectionDraft): SavedConnectionSnapshot {
        validateDraft(draft)
        val entity = draft.toEntity()
        dao.insertAndSelectCurrent(entity)
        return entity.toSnapshot(isCurrent = true)
    }

    suspend fun list(): List<SavedConnectionSnapshot> {
        val stored = dao.snapshot()
        return stored.connections.mapNotNull { entity ->
            entity.toSnapshotOrNull(entity.connectionId == stored.currentConnectionId)
        }.sortedWith(
            compareByDescending<SavedConnectionSnapshot> { it.isCurrent }
                .thenByDescending { it.updatedAt }
                .thenBy { it.connectionId },
        )
    }

    suspend fun selectCurrent(connectionId: String, now: Long) {
        require(now >= 0)
        dao.selectCurrent(connectionId, now)
    }

    suspend fun referencesSecret(secretRefId: String): Boolean = dao.countBySecretRef(secretRefId) > 0

    suspend fun acceptDataDisclosure(
        connectionId: String,
        now: Long,
    ): AcceptedDataDisclosureEvidence = dao.acceptDataDisclosureForCurrentDestination(connectionId, now)

    suspend fun readAcceptedDataDisclosureEvidence(
        connectionId: String,
    ): AcceptedDataDisclosureEvidence = dao.readAcceptedDataDisclosureEvidence(connectionId)

    suspend fun edit(
        connectionId: String,
        displayName: String,
        selectedModelId: String,
        now: Long,
    ) {
        val name = displayName.trim()
        require(name.length in 1..MAX_DISPLAY_NAME_LENGTH)
        val model = ProviderModelId.from(selectedModelId.trim()).withValue { it }
        val current = requireNotNull(dao.findConnection(connectionId)) { "Connection does not exist." }
        val available = decodeModels(current.availableModelsJson)
        require(available.isEmpty() || model in available) { "Model was not returned by this connection." }
        val unchanged = model == current.selectedModelId
        val verification = when {
            unchanged -> current.modelVerification
            available.isEmpty() -> ConnectionModelVerification.MANUAL_UNVERIFIED.name
            else -> ConnectionModelVerification.DISCOVERED.name
        }
        dao.editConnection(
            connectionId = connectionId,
            displayName = name,
            selectedModelId = model,
            modelVerification = verification,
            fullVerifiedAt = if (unchanged) current.fullVerifiedAt else null,
            updatedAt = now,
        )
    }

    suspend fun delete(connectionId: String, now: Long): ConnectionProfileEntity =
        dao.deleteAndChooseFallback(connectionId, now).deleted

    private fun validateDraft(draft: PersistentConnectionDraft) {
        require(draft.connectionId.isNotBlank())
        require(draft.displayName.trim().length in 1..MAX_DISPLAY_NAME_LENGTH)
        require(draft.secretRefId.isNotBlank())
        require(draft.secretLastFour.length in 1..4)
        val selected = ProviderModelId.from(draft.selectedModelId).withValue { it }
        require(draft.availableModels.isEmpty() || selected in draft.availableModels)
        require(draft.createdAt >= 0 && draft.basicVerifiedAt >= 0)
        require(draft.fullVerifiedAt == null || draft.fullVerifiedAt >= draft.basicVerifiedAt)
    }

    private fun PersistentConnectionDraft.toEntity(): ConnectionProfileEntity {
        val destination = ExternalDataDestinationBindingV1.create(
            baseUrl = baseUrl,
            protocolId = protocol.name,
        )
        return ConnectionProfileEntity(
            connectionId = connectionId,
            displayName = displayName.trim(),
            serviceId = service.name,
            protocolId = protocol.name,
            baseUrl = baseUrl,
            normalizedDestination = destination.normalizedDestination,
            secretRefId = secretRefId,
            secretLastFour = secretLastFour,
            selectedModelId = selectedModelId,
            availableModelsJson = encodeModels(availableModels),
            modelVerification = modelVerification.name,
            basicVerifiedAt = basicVerifiedAt,
            fullVerifiedAt = fullVerifiedAt,
            dataDisclosureVersion = null,
            dataDisclosureAcceptedAt = null,
            dataDisclosureBindingHash = null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    private fun ConnectionProfileEntity.toSnapshotOrNull(isCurrent: Boolean): SavedConnectionSnapshot? =
        runCatching { toSnapshot(isCurrent) }.getOrNull()

    private fun ConnectionProfileEntity.toSnapshot(isCurrent: Boolean) = SavedConnectionSnapshot(
        connectionId = connectionId,
        displayName = displayName,
        service = enumValueOf(serviceId),
        protocol = enumValueOf(protocolId),
        protocolId = protocolId,
        baseUrl = baseUrl,
        endpointHost = requireNotNull(URI(baseUrl).host),
        secretLastFour = secretLastFour,
        selectedModelId = selectedModelId,
        availableModels = decodeModels(availableModelsJson),
        modelVerification = enumValueOf(modelVerification),
        isCurrent = isCurrent,
        updatedAt = updatedAt,
    )

    private fun encodeModels(models: List<String>): String = JSONArray().apply {
        models.distinct().take(MAX_STORED_MODELS).forEach(::put)
    }.toString()

    private fun decodeModels(value: String): List<String> {
        val array = JSONArray(value)
        return buildList(array.length()) {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 80
        const val MAX_STORED_MODELS = 500
    }
}
