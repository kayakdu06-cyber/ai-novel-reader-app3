package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.contract.GenerationStartRequest
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.BudgetDailyPeriodKeyV1
import app.zhijuan.core.model.BudgetLimit
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.ExternalDataDestinationBindingV1
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class GenerationStartPersistenceFailure {
    BOOK_NOT_FOUND,
    CONFIRMATION_CHANGED,
    CONNECTION_CHANGED,
    DESTINATION_CONFIRMATION_REQUIRED,
    BUDGET_CONFIRMATION_INVALID,
}

sealed interface GenerationStartPersistenceResult {
    data class Started(val bookId: String, val jobId: String, val replayed: Boolean) :
        GenerationStartPersistenceResult

    data class Failed(val reason: GenerationStartPersistenceFailure) : GenerationStartPersistenceResult
}

/** Atomically validates one frozen confirmation and creates its unique initial-planning Job. */
class GenerationStartPersistenceRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun start(request: GenerationStartRequest): GenerationStartPersistenceResult = try {
        database.withTransaction { startInTransaction(request) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: StartRejected) {
        GenerationStartPersistenceResult.Failed(failure.reason)
    }

    private suspend fun startInTransaction(request: GenerationStartRequest): GenerationStartPersistenceResult {
        if (!BudgetDailyPeriodKeyV1.isSupportedZoneId(request.budget.dailyZoneId)) {
            reject(GenerationStartPersistenceFailure.BUDGET_CONFIRMATION_INVALID)
        }
        val library = database.libraryDao()
        val book = library.findBook(request.bookId)
            ?: reject(GenerationStartPersistenceFailure.BOOK_NOT_FOUND)
        if (book.deletedAt != null || book.archivedAt != null) {
            reject(GenerationStartPersistenceFailure.BOOK_NOT_FOUND)
        }
        val snapshot = library.findCreationSnapshot(request.creationSnapshotId)
            ?: reject(GenerationStartPersistenceFailure.CONFIRMATION_CHANGED)
        if (
            book.creationSnapshotId != request.creationSnapshotId ||
            snapshot.snapshotId != request.creationSnapshotId ||
            snapshot.contentHash != request.creationSnapshotContentHash ||
            book.status !in setOf(BookStatus.DRAFT, BookStatus.GENERATING)
        ) {
            reject(GenerationStartPersistenceFailure.CONFIRMATION_CHANGED)
        }
        val frozenModel = parseFrozenModel(snapshot.modelPreferenceJson)
        if (frozenModel.first != request.connectionId || frozenModel.second != request.modelId) {
            reject(GenerationStartPersistenceFailure.CONFIRMATION_CHANGED)
        }

        val connections = database.connectionDao()
        val connection = connections.findConnection(request.connectionId)
            ?: reject(GenerationStartPersistenceFailure.CONNECTION_CHANGED)
        if (
            connections.currentConnectionId() != request.connectionId ||
            connection.selectedModelId != request.modelId ||
            connection.protocolId != request.destinationProtocolId
        ) {
            reject(GenerationStartPersistenceFailure.CONNECTION_CHANGED)
        }
        val expectedDestination = runCatching {
            ExternalDataDestinationBindingV1.create(connection.baseUrl, connection.protocolId)
        }.getOrElse { reject(GenerationStartPersistenceFailure.CONNECTION_CHANGED) }
        if (
            !expectedDestination.matches(
                normalizedDestination = request.normalizedDestination,
                protocolId = request.destinationProtocolId,
                disclosureVersion = request.destinationDisclosureVersion,
                bindingHash = request.destinationBindingHash,
            )
        ) {
            reject(GenerationStartPersistenceFailure.CONNECTION_CHANGED)
        }
        val accepted = when {
            connection.dataDisclosureVersion == null &&
                connection.dataDisclosureAcceptedAt == null &&
                connection.dataDisclosureBindingHash == null ->
                connections.acceptDataDisclosureForCurrentDestination(request.connectionId, request.confirmedAt)
            connection.dataDisclosureVersion != null &&
                connection.dataDisclosureAcceptedAt != null &&
                connection.dataDisclosureBindingHash != null ->
                runCatching { connections.readAcceptedDataDisclosureEvidence(request.connectionId) }
                    .getOrElse { reject(GenerationStartPersistenceFailure.DESTINATION_CONFIRMATION_REQUIRED) }
            else -> reject(GenerationStartPersistenceFailure.DESTINATION_CONFIRMATION_REQUIRED)
        }
        if (
            accepted.normalizedDestination != request.normalizedDestination ||
            accepted.protocolId != request.destinationProtocolId ||
            accepted.disclosureVersion != request.destinationDisclosureVersion ||
            accepted.bindingHash != request.destinationBindingHash
        ) {
            reject(GenerationStartPersistenceFailure.DESTINATION_CONFIRMATION_REQUIRED)
        }

        val bound = runCatching { PromptBundleBindingRepository(database).bindForBook(book.bookId) }
            .getOrElse { reject(GenerationStartPersistenceFailure.CONFIRMATION_CHANGED) }
        if (bound.sourceContentHash != snapshot.contentHash) {
            reject(GenerationStartPersistenceFailure.CONFIRMATION_CHANGED)
        }
        val identity = identity(request.bookId, request.creationSnapshotId, request.creationSnapshotContentHash)
        val budgetSnapshot = budgetSnapshot(request)
        val setup = InitialPlanningJobFactory.create(
            InitialPlanningJobSpec(
                jobId = identity.jobId,
                bookId = book.bookId,
                creationSnapshotId = snapshot.snapshotId,
                creationSnapshotHash = snapshot.contentHash,
                promptBundleBindingHash = bound.bindingHash,
                targetChapterCount = bound.targetChapterCount,
                userIntentJson = snapshot.normalizedInputJson,
                budgetSnapshotJson = budgetSnapshot,
                stageIds = identity.stageIds,
                createdAt = request.confirmedAt,
            ),
        )
        val existing = database.generationDao().findJob(identity.jobId)
        if (existing != null) {
            requireExactReplay(setup, existing, budgetSnapshot)
            requireCurrentBudget(request)
            return GenerationStartPersistenceResult.Started(book.bookId, identity.jobId, replayed = true)
        }

        val policies = PersistentBudgetPolicyRepository(database)
        policies.activateBookPolicy(
            policyId = "budget.book.${identity.suffix}",
            bookId = book.bookId,
            limit = BudgetLimit(request.budget.bookTokenHardLimit),
            activatedAt = request.confirmedAt,
        )
        policies.activateDailyPolicy(
            policyId = "budget.daily.${identity.suffix}",
            zoneId = request.budget.dailyZoneId,
            limit = BudgetLimit(request.budget.dailyTokenHardLimit),
            activatedAt = request.confirmedAt,
        )
        GenerationJobSetupRepository(database).create(setup)
        val dao = database.generationDao()
        dao.transitionStage(
            stageId = identity.stageIds.seedStageId,
            expectedStatus = GenerationStageStatus.PENDING,
            event = StageEvent.DEPENDENCIES_SATISFIED,
            updatedAt = request.confirmedAt,
        )
        dao.transitionJob(
            jobId = identity.jobId,
            expectedStatus = GenerationJobStatus.CREATED,
            event = JobEvent.VALIDATION_PASSED,
            updatedAt = request.confirmedAt,
        )
        check(
            library.updateBookAfterGeneratedChapter(
                bookId = book.bookId,
                completedChapterIncrement = 0,
                status = BookStatus.GENERATING,
                generationStatusSummary = "INITIAL_PLANNING_READY",
                updatedAt = request.confirmedAt,
            ) == 1,
        ) { "Generation start lost the owning book update." }
        return GenerationStartPersistenceResult.Started(book.bookId, identity.jobId, replayed = false)
    }

    private suspend fun requireCurrentBudget(request: GenerationStartRequest) {
        val policies = PersistentBudgetPolicyRepository(database)
        val book = policies.currentBookPolicy(request.bookId)
        val daily = policies.currentDailyPolicy(request.budget.dailyZoneId)
        if (
            book?.maxTokens != request.budget.bookTokenHardLimit || book.hasMonetaryLimit ||
            daily?.maxTokens != request.budget.dailyTokenHardLimit || daily.hasMonetaryLimit
        ) {
            reject(GenerationStartPersistenceFailure.BUDGET_CONFIRMATION_INVALID)
        }
    }

    private suspend fun requireExactReplay(
        expected: GenerationJobSetup,
        actual: GenerationJobEntity,
        budgetSnapshot: String,
    ) {
        if (
            actual.bookId != expected.bookId ||
            actual.jobType != expected.jobType ||
            actual.userIntentJson != expected.userIntentJson ||
            actual.budgetSnapshotJson != budgetSnapshot ||
            actual.promptBundleVersion != PromptBundleCatalogV1.BUNDLE_VERSION
        ) {
            reject(GenerationStartPersistenceFailure.CONFIRMATION_CHANGED)
        }
        val actualStages = database.generationDao().stagesForJob(actual.jobId)
            .associateBy(GenerationStageEntity::stageId)
        if (actualStages.size != expected.stages.size || expected.stages.any { left ->
                val right = actualStages[left.stageId]
                    ?: return@any true
                left.phase != right.phase ||
                    left.targetType != right.targetType || left.targetId != right.targetId ||
                    left.inputVersionHash != right.inputVersionHash ||
                    left.idempotencyKey != right.idempotencyKey ||
                    left.maxAttempts != right.maxAttempts || left.inputSourcesJson != right.inputSourcesJson
            }
        ) {
            reject(GenerationStartPersistenceFailure.CONFIRMATION_CHANGED)
        }
    }

    private fun parseFrozenModel(value: String): Pair<String, String> {
        val root = runCatching { Json.parseToJsonElement(value) as JsonObject }
            .getOrElse { reject(GenerationStartPersistenceFailure.CONFIRMATION_CHANGED) }
        val connectionId = (root["connectionId"] as? JsonPrimitive)?.content
        val modelId = (root["modelId"] as? JsonPrimitive)?.content
        if (connectionId.isNullOrBlank() || modelId.isNullOrBlank()) {
            reject(GenerationStartPersistenceFailure.CONFIRMATION_CHANGED)
        }
        return connectionId to modelId
    }

    private fun budgetSnapshot(request: GenerationStartRequest): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "requestTokenHardLimit" to JsonPrimitive(request.budget.requestTokenHardLimit),
            "bookTokenHardLimit" to JsonPrimitive(request.budget.bookTokenHardLimit),
            "dailyTokenHardLimit" to JsonPrimitive(request.budget.dailyTokenHardLimit),
            "dailyZoneId" to JsonPrimitive(request.budget.dailyZoneId),
            "priceUnknownAccepted" to JsonPrimitive(request.budget.priceUnknownAccepted),
            "creationSnapshotId" to JsonPrimitive(request.creationSnapshotId),
            "creationSnapshotContentHash" to JsonPrimitive(request.creationSnapshotContentHash),
            "connectionId" to JsonPrimitive(request.connectionId),
            "modelId" to JsonPrimitive(request.modelId),
            "normalizedDestination" to JsonPrimitive(request.normalizedDestination),
            "destinationProtocolId" to JsonPrimitive(request.destinationProtocolId),
            "destinationDisclosureVersion" to JsonPrimitive(request.destinationDisclosureVersion),
            "destinationBindingHash" to JsonPrimitive(request.destinationBindingHash),
        ),
    ).toString()

    private fun identity(bookId: String, snapshotId: String, contentHash: String): StartIdentity {
        val suffix = sha256("zhijuan.generation-start.v1\u0000$bookId\u0000$snapshotId\u0000$contentHash").take(32)
        return StartIdentity(
            suffix = suffix,
            jobId = "job.start.$suffix",
            stageIds = InitialPlanningStageIds(
                seedStageId = "stage.seed.$suffix",
                bibleStageId = "stage.bible.$suffix",
                outlineStageId = "stage.outline.$suffix",
            ),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun reject(reason: GenerationStartPersistenceFailure): Nothing = throw StartRejected(reason)

    private data class StartIdentity(
        val suffix: String,
        val jobId: String,
        val stageIds: InitialPlanningStageIds,
    )

    private class StartRejected(val reason: GenerationStartPersistenceFailure) : RuntimeException()
}
