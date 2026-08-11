package app.zhijuan.core.database.capability

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ProviderCapabilityDao {
    @Query(
        """
        SELECT * FROM provider_capability
        WHERE connection_id = :connectionId
          AND endpoint_fingerprint = :endpointFingerprint
          AND protocol_id = :protocolId
          AND model_id = :modelId
        """,
    )
    suspend fun load(
        connectionId: String,
        endpointFingerprint: String,
        protocolId: String,
        modelId: String,
    ): List<ProviderCapabilityEntity>

    @Query(
        """
        SELECT * FROM provider_capability
        WHERE connection_id = :connectionId
          AND endpoint_fingerprint = :endpointFingerprint
          AND protocol_id = :protocolId
          AND model_id = :modelId
          AND capability_source = :source
        """,
    )
    suspend fun find(
        connectionId: String,
        endpointFingerprint: String,
        protocolId: String,
        modelId: String,
        source: String,
    ): ProviderCapabilityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entity: ProviderCapabilityEntity)

    @Query(
        """
        DELETE FROM provider_capability
        WHERE connection_id = :connectionId
          AND endpoint_fingerprint = :endpointFingerprint
          AND protocol_id = :protocolId
          AND model_id = :modelId
          AND capability_source = :source
        """,
    )
    suspend fun delete(
        connectionId: String,
        endpointFingerprint: String,
        protocolId: String,
        modelId: String,
        source: String,
    ): Int

    @Transaction
    suspend fun upsertNewest(entity: ProviderCapabilityEntity) {
        val current = find(
            connectionId = entity.connectionId,
            endpointFingerprint = entity.endpointFingerprint,
            protocolId = entity.protocolId,
            modelId = entity.modelId,
            source = entity.capabilitySource,
        )
        if (current == null || entity.verifiedAt >= current.verifiedAt) replace(entity)
    }
}
