package app.zhijuan.core.model

/**
 * Redacted evidence of the connection and canonical remote origin an executor
 * is about to open. The raw base URL is used only while constructing this
 * object and is never retained.
 *
 * This evidence is not a send permit. Provider-open still has to match it
 * against both the frozen budget reservation and the current accepted data
 * disclosure inside the database transaction that grants the one-shot permit.
 */
class ProviderOpenDestinationEvidence private constructor(
    val connectionId: String,
    val normalizedDestination: String,
    val protocolId: String,
) {
    fun matches(other: ProviderOpenDestinationEvidence): Boolean =
        connectionId == other.connectionId &&
            normalizedDestination == other.normalizedDestination &&
            protocolId == other.protocolId

    override fun toString(): String = "ProviderOpenDestinationEvidence(redacted=true)"

    companion object {
        private val CONNECTION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

        fun create(
            connectionId: String,
            baseUrl: String,
            protocolId: String,
        ): ProviderOpenDestinationEvidence {
            require(connectionId.matches(CONNECTION_ID_PATTERN)) { "Connection id is invalid." }
            val binding = ExternalDataDestinationBindingV1.create(
                baseUrl = baseUrl,
                protocolId = protocolId,
            )
            return ProviderOpenDestinationEvidence(
                connectionId = connectionId,
                normalizedDestination = binding.normalizedDestination,
                protocolId = binding.protocolId,
            )
        }
    }
}
