package app.zhijuan.core.database.connection

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.zhijuan.core.model.ExternalDataDestinationBindingV1

data class StoredConnectionsSnapshot(
    val connections: List<ConnectionProfileEntity>,
    val currentConnectionId: String?,
)

data class DeletedConnectionResult(
    val deleted: ConnectionProfileEntity,
    val newCurrentConnectionId: String?,
)

data class AcceptedDataDisclosureEvidence(
    val connectionId: String,
    val normalizedDestination: String,
    val protocolId: String,
    val disclosureVersion: Int,
    val acceptedAt: Long,
    val bindingHash: String,
) {
    override fun toString(): String =
        "AcceptedDataDisclosureEvidence(disclosureVersion=$disclosureVersion, redacted=true)"
}

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connection_profile ORDER BY updated_at DESC, connection_id ASC")
    suspend fun listConnections(): List<ConnectionProfileEntity>

    @Query("SELECT * FROM connection_profile WHERE connection_id = :connectionId")
    suspend fun findConnection(connectionId: String): ConnectionProfileEntity?

    @Query("SELECT COUNT(*) FROM connection_profile WHERE secret_ref_id = :secretRefId")
    suspend fun countBySecretRef(secretRefId: String): Int

    @Query(
        "SELECT connection_id FROM current_connection_selection " +
            "WHERE singleton_id = ${CurrentConnectionSelectionEntity.SINGLETON_ID}",
    )
    suspend fun currentConnectionId(): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConnection(connection: ConnectionProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceCurrentSelection(selection: CurrentConnectionSelectionEntity)

    @Query("DELETE FROM current_connection_selection WHERE singleton_id = ${CurrentConnectionSelectionEntity.SINGLETON_ID}")
    suspend fun clearCurrentSelection(): Int

    @Query("DELETE FROM connection_profile WHERE connection_id = :connectionId")
    suspend fun deleteConnection(connectionId: String): Int

    @Query(
        """
        UPDATE connection_profile
        SET normalized_destination = :normalizedDestination,
            data_disclosure_version = :disclosureVersion,
            data_disclosure_accepted_at = :acceptedAt,
            data_disclosure_binding_hash = :bindingHash
        WHERE connection_id = :connectionId
          AND base_url = :expectedBaseUrl
          AND protocol_id = :expectedProtocolId
        """,
    )
    suspend fun updateDataDisclosureIfDestinationUnchanged(
        connectionId: String,
        expectedBaseUrl: String,
        expectedProtocolId: String,
        normalizedDestination: String,
        disclosureVersion: Int,
        acceptedAt: Long,
        bindingHash: String,
    ): Int

    @Query(
        """
        UPDATE connection_profile
        SET display_name = :displayName,
            selected_model_id = :selectedModelId,
            model_verification = :modelVerification,
            full_verified_at = :fullVerifiedAt,
            updated_at = :updatedAt
        WHERE connection_id = :connectionId
        """,
    )
    suspend fun updateEditableFields(
        connectionId: String,
        displayName: String,
        selectedModelId: String,
        modelVerification: String,
        fullVerifiedAt: Long?,
        updatedAt: Long,
    ): Int

    @Transaction
    suspend fun snapshot(): StoredConnectionsSnapshot = StoredConnectionsSnapshot(
        connections = listConnections(),
        currentConnectionId = currentConnectionId(),
    )

    @Transaction
    suspend fun insertAndSelectCurrent(connection: ConnectionProfileEntity) {
        require(connection.connectionId.isNotBlank())
        insertConnection(connection)
        replaceCurrentSelection(
            CurrentConnectionSelectionEntity(
                connectionId = connection.connectionId,
                updatedAt = connection.updatedAt,
            ),
        )
    }

    @Transaction
    suspend fun selectCurrent(connectionId: String, updatedAt: Long) {
        requireNotNull(findConnection(connectionId)) { "Connection does not exist." }
        replaceCurrentSelection(
            CurrentConnectionSelectionEntity(
                connectionId = connectionId,
                updatedAt = updatedAt,
            ),
        )
    }

    @Transaction
    suspend fun editConnection(
        connectionId: String,
        displayName: String,
        selectedModelId: String,
        modelVerification: String,
        fullVerifiedAt: Long?,
        updatedAt: Long,
    ) {
        require(displayName.isNotBlank() && selectedModelId.isNotBlank())
        check(
            updateEditableFields(
                connectionId = connectionId,
                displayName = displayName,
                selectedModelId = selectedModelId,
                modelVerification = modelVerification,
                fullVerifiedAt = fullVerifiedAt,
                updatedAt = updatedAt,
            ) == 1,
        ) { "Connection does not exist." }
    }

    @Transaction
    suspend fun acceptDataDisclosureForCurrentDestination(
        connectionId: String,
        acceptedAt: Long,
    ): AcceptedDataDisclosureEvidence {
        val current = requireNotNull(findConnection(connectionId)) { "Connection does not exist." }
        require(acceptedAt >= current.createdAt) { "Data disclosure acceptance time is invalid." }
        val binding = ExternalDataDestinationBindingV1.create(
            baseUrl = current.baseUrl,
            protocolId = current.protocolId,
        )
        check(
            updateDataDisclosureIfDestinationUnchanged(
                connectionId = connectionId,
                expectedBaseUrl = current.baseUrl,
                expectedProtocolId = current.protocolId,
                normalizedDestination = binding.normalizedDestination,
                disclosureVersion = binding.disclosureVersion,
                acceptedAt = acceptedAt,
                bindingHash = binding.bindingHash,
            ) == 1,
        ) { "Connection destination changed before disclosure acceptance." }
        return requireNotNull(findConnection(connectionId)).toAcceptedDataDisclosureEvidence()
    }

    @Transaction
    suspend fun readAcceptedDataDisclosureEvidence(
        connectionId: String,
    ): AcceptedDataDisclosureEvidence =
        requireNotNull(findConnection(connectionId)) { "Connection does not exist." }
            .toAcceptedDataDisclosureEvidence()

    @Transaction
    suspend fun deleteAndChooseFallback(connectionId: String, updatedAt: Long): DeletedConnectionResult {
        val existing = requireNotNull(findConnection(connectionId)) { "Connection does not exist." }
        val wasCurrent = currentConnectionId() == connectionId
        val fallback = if (wasCurrent) {
            listConnections().firstOrNull { candidate -> candidate.connectionId != connectionId }
        } else {
            null
        }
        if (wasCurrent) {
            if (fallback == null) {
                clearCurrentSelection()
            } else {
                replaceCurrentSelection(
                    CurrentConnectionSelectionEntity(
                        connectionId = fallback.connectionId,
                        updatedAt = updatedAt,
                    ),
                )
            }
        }
        check(deleteConnection(connectionId) == 1)
        return DeletedConnectionResult(existing, fallback?.connectionId)
    }
}

private fun ConnectionProfileEntity.toAcceptedDataDisclosureEvidence(): AcceptedDataDisclosureEvidence {
    val version = dataDisclosureVersion
    val acceptedAt = dataDisclosureAcceptedAt
    val storedHash = dataDisclosureBindingHash
    check(version != null && acceptedAt != null && storedHash != null) {
        "Data disclosure has not been accepted."
    }
    check(version == ExternalDataDestinationBindingV1.CURRENT_DISCLOSURE_VERSION) {
        "Data disclosure evidence is not current."
    }
    check(acceptedAt >= createdAt) { "Data disclosure evidence is invalid." }
    val binding = runCatching {
        ExternalDataDestinationBindingV1.create(
            baseUrl = baseUrl,
            protocolId = protocolId,
            disclosureVersion = version,
        )
    }.getOrElse { throw IllegalStateException("Data disclosure evidence is invalid.", it) }
    val validatedHash = runCatching {
        ExternalDataDestinationBindingV1.requireValidStoredHash(storedHash)
    }.getOrElse { throw IllegalStateException("Data disclosure evidence is invalid.", it) }
    check(
        binding.matches(
            normalizedDestination = normalizedDestination,
            protocolId = protocolId,
            disclosureVersion = version,
            bindingHash = validatedHash,
        ),
    ) { "Data disclosure evidence does not match the current destination." }
    return AcceptedDataDisclosureEvidence(
        connectionId = connectionId,
        normalizedDestination = binding.normalizedDestination,
        protocolId = binding.protocolId,
        disclosureVersion = binding.disclosureVersion,
        acceptedAt = acceptedAt,
        bindingHash = binding.bindingHash,
    )
}
