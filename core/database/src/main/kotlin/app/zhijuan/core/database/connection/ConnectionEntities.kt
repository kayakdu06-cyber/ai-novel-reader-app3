package app.zhijuan.core.database.connection

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "connection_profile",
    indices = [
        Index(value = ["secret_ref_id"], unique = true),
        Index(value = ["service_id"]),
        Index(value = ["updated_at"]),
    ],
)
data class ConnectionProfileEntity(
    @PrimaryKey @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "protocol_id") val protocolId: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "normalized_destination") val normalizedDestination: String,
    @ColumnInfo(name = "secret_ref_id") val secretRefId: String,
    @ColumnInfo(name = "secret_last_four") val secretLastFour: String,
    @ColumnInfo(name = "selected_model_id") val selectedModelId: String,
    @ColumnInfo(name = "available_models_json") val availableModelsJson: String,
    @ColumnInfo(name = "model_verification") val modelVerification: String,
    @ColumnInfo(name = "basic_verified_at") val basicVerifiedAt: Long,
    @ColumnInfo(name = "full_verified_at") val fullVerifiedAt: Long?,
    @ColumnInfo(name = "data_disclosure_version") val dataDisclosureVersion: Int?,
    @ColumnInfo(name = "data_disclosure_accepted_at") val dataDisclosureAcceptedAt: Long?,
    @ColumnInfo(name = "data_disclosure_binding_hash") val dataDisclosureBindingHash: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun toString(): String =
        "ConnectionProfileEntity(disclosureAccepted=${dataDisclosureAcceptedAt != null}, redacted=true)"
}

@Entity(
    tableName = "current_connection_selection",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionProfileEntity::class,
            parentColumns = ["connection_id"],
            childColumns = ["connection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["connection_id"], unique = true)],
)
data class CurrentConnectionSelectionEntity(
    @PrimaryKey @ColumnInfo(name = "singleton_id") val singletonId: Int = SINGLETON_ID,
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
