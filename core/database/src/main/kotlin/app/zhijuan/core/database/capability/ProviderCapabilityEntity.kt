package app.zhijuan.core.database.capability

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "provider_capability",
    primaryKeys = [
        "connection_id",
        "endpoint_fingerprint",
        "protocol_id",
        "model_id",
        "capability_source",
    ],
    indices = [
        Index(value = ["connection_id", "protocol_id", "model_id"]),
        Index(value = ["expires_at"]),
        Index(value = ["adapter_version"]),
    ],
)
data class ProviderCapabilityEntity(
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "endpoint_fingerprint") val endpointFingerprint: String,
    @ColumnInfo(name = "protocol_id") val protocolId: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "capability_source") val capabilitySource: String,
    @ColumnInfo(name = "streaming_support") val streamingSupport: String,
    @ColumnInfo(name = "stream_format") val streamFormat: String,
    @ColumnInfo(name = "structured_output_support") val structuredOutputSupport: String,
    @ColumnInfo(name = "stream_usage_support") val streamUsageSupport: String,
    @ColumnInfo(name = "system_instruction_support") val systemInstructionSupport: String,
    @ColumnInfo(name = "temperature_support") val temperatureSupport: String,
    @ColumnInfo(name = "top_p_support") val topPSupport: String,
    @ColumnInfo(name = "max_output_tokens_parameter_support") val maxOutputTokensParameterSupport: String,
    @ColumnInfo(name = "seed_support") val seedSupport: String,
    @ColumnInfo(name = "reasoning_effort_support") val reasoningEffortSupport: String,
    @ColumnInfo(name = "idempotency_key_support") val idempotencyKeySupport: String,
    @ColumnInfo(name = "context_limit") val contextLimit: Int?,
    @ColumnInfo(name = "max_output_tokens") val maxOutputTokens: Int?,
    @ColumnInfo(name = "tokenizer_family") val tokenizerFamily: String,
    @ColumnInfo(name = "verified_at") val verifiedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
    @ColumnInfo(name = "adapter_version") val adapterVersion: String,
    @ColumnInfo(name = "risk_acknowledged_at") val riskAcknowledgedAt: Long?,
)
