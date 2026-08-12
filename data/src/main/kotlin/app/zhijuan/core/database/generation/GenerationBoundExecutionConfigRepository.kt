package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

data class GenerationBoundExecutionConfig(
    val connectionId: String,
    val protocolId: String,
    val baseUrl: String,
    val normalizedDestination: String,
    val secretRefId: String,
    val modelId: String,
    val modelVerification: String,
    val disclosureVersion: Int,
    val disclosureBindingHash: String,
    val requestMaximumTokens: Long,
) {
    override fun toString(): String = "GenerationBoundExecutionConfig(content=redacted)"
}

/** Reloads the exact confirmed execution target while both runner leases are still authoritative. */
class GenerationBoundExecutionConfigRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun load(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        loadedAt: Long,
    ): GenerationBoundExecutionConfig = database.withTransaction {
        val lease = snapshot.executionLease
        val dao = database.generationDao()
        val job = requireNotNull(dao.findJob(lease.jobId)) { "Generation Job is missing." }
        val stage = requireNotNull(dao.findStage(lease.stageId)) { "Generation Stage is missing." }
        val jobHeartbeat = requireNotNull(job.leaseHeartbeatAt)
        val stageHeartbeat = requireNotNull(stage.leaseHeartbeatAt)
        if (
            job.status != GenerationJobStatus.RUNNING || stage.status != GenerationStageStatus.PREPARING ||
            job.currentStageId != stage.stageId || stage.jobId != job.jobId || job.pauseOrStopReason != null ||
            job.leaseTokenOrNull() != lease.jobLeaseToken || stage.leaseTokenOrNull() != lease.stageLeaseToken ||
            jobHeartbeat < lease.jobHeartbeatAt || stageHeartbeat < lease.stageHeartbeatAt ||
            stage.attemptCount != snapshot.attemptCount || stage.maxAttempts != snapshot.maxAttempts ||
            GenerationRunnerStageRouteResolver.resolve(stage) != snapshot.route
        ) throw StaleGenerationStateException("Generation execution target changed.")
        require(
            loadedAt >= job.updatedAt && loadedAt >= stage.updatedAt &&
                loadedAt >= jobHeartbeat && loadedAt >= stageHeartbeat,
        ) { "Generation execution target time cannot move backwards." }
        if (leasePolicy.isExpired(jobHeartbeat, loadedAt) || leasePolicy.isExpired(stageHeartbeat, loadedAt)) {
            throw StaleGenerationStateException("Generation execution lease expired before target load.")
        }

        val budget = strictObject(job.budgetSnapshotJson)
        val connectionId = budget.string("connectionId")
        val modelId = budget.string("modelId")
        val connection = requireNotNull(database.connectionDao().findConnection(connectionId)) {
            "Generation connection is missing."
        }
        require(
            database.connectionDao().currentConnectionId() == connectionId &&
                connection.selectedModelId == modelId && connection.protocolId == budget.string("destinationProtocolId") &&
                connection.normalizedDestination == budget.string("normalizedDestination") &&
                connection.dataDisclosureVersion == budget.int("destinationDisclosureVersion") &&
                connection.dataDisclosureBindingHash == budget.string("destinationBindingHash"),
        ) { "Generation connection changed after confirmation." }
        GenerationBoundExecutionConfig(
            connectionId = connection.connectionId,
            protocolId = connection.protocolId,
            baseUrl = connection.baseUrl,
            normalizedDestination = connection.normalizedDestination,
            secretRefId = connection.secretRefId,
            modelId = modelId,
            modelVerification = connection.modelVerification,
            disclosureVersion = requireNotNull(connection.dataDisclosureVersion),
            disclosureBindingHash = requireNotNull(connection.dataDisclosureBindingHash),
            requestMaximumTokens = budget.long("requestTokenHardLimit").also { require(it > 0L) },
        )
    }

    private fun strictObject(value: String): JsonObject =
        runCatching { Json.parseToJsonElement(value) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Generation budget snapshot is invalid.") }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: throw IllegalArgumentException("Generation snapshot string is invalid.")

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.long
            ?: throw IllegalArgumentException("Generation snapshot integer is invalid.")

    private fun JsonObject.int(key: String): Int = long(key).toInt()
}
