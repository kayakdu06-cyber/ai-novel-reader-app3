package app.zhijuan.core.database.connection

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

data class StoredConnectionsSnapshot(
    val connections: List<ConnectionProfileEntity>,
    val currentConnectionId: String?,
)

data class DeletedConnectionResult(
    val deleted: ConnectionProfileEntity,
    val newCurrentConnectionId: String?,
)

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
