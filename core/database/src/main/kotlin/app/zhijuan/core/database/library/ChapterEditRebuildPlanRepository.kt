package app.zhijuan.core.database.library

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.ChapterEditRebuildStageRepository
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class FutureChapterPolicy {
    KEEP_EXISTING,
    REGENERATE_FROM_NEXT,
}

enum class ChapterEditRebuildStepType {
    EXTRACT_EDITED_MEMORY,
    REBUILD_STORY_TRACKING,
    REASSEMBLE_CONTEXT,
    RECHECK_CONSISTENCY,
    REBUILD_AGGREGATE_STATE,
}

enum class ChapterEditRebuildStepState {
    READY,
    WAITING_FOR_DEPENDENCY,
    ALREADY_SATISFIED,
    BLOCKED,
}

enum class ChapterEditRebuildBlocker {
    NONE,
    DERIVED_VERSION_SLOT_OCCUPIED,
    TRACKING_ORDER_GUARD,
    DEPENDENCY_BLOCKED,
}

data class ChapterEditRebuildPlanRequest(
    val bookId: String,
    val editedChapterId: String,
    val editedVersionId: String,
    val futureChapterPolicy: FutureChapterPolicy = FutureChapterPolicy.KEEP_EXISTING,
) {
    init {
        require(
            IDENTIFIER.matches(bookId) &&
                IDENTIFIER.matches(editedChapterId) &&
                IDENTIFIER.matches(editedVersionId),
        ) { "Chapter rebuild plan identifiers are invalid." }
    }

    override fun toString(): String =
        "ChapterEditRebuildPlanRequest(policy=$futureChapterPolicy, identifiers=redacted)"
}

data class ChapterEditRebuildFrozenChapter(
    val chapterIndex: Int,
    val chapterIdHash: String,
    val currentVersionIdHash: String,
    val contentHash: String,
    val status: ChapterStatus,
    val consistencyStatus: ConsistencyStatus,
) {
    override fun toString(): String =
        "ChapterEditRebuildFrozenChapter(chapterIndex=$chapterIndex, status=$status, " +
            "consistencyStatus=$consistencyStatus, hashes=redacted)"
}

data class ChapterEditRebuildStep(
    val ordinal: Int,
    val type: ChapterEditRebuildStepType,
    val chapterIndex: Int,
    val sourceVersionIdHash: String,
    val sourceContentHash: String,
    val needsProvider: Boolean,
    val state: ChapterEditRebuildStepState,
    val blocker: ChapterEditRebuildBlocker,
    val dependsOnOrdinals: List<Int>,
) {
    init {
        require(ordinal > 0 && chapterIndex > 0)
        require(HASH.matches(sourceVersionIdHash) && HASH.matches(sourceContentHash))
        require(dependsOnOrdinals.all { it in 1 until ordinal } && dependsOnOrdinals.distinct().size == dependsOnOrdinals.size)
        require((state == ChapterEditRebuildStepState.BLOCKED) == (blocker != ChapterEditRebuildBlocker.NONE))
    }

    override fun toString(): String =
        "ChapterEditRebuildStep(ordinal=$ordinal, type=$type, chapterIndex=$chapterIndex, " +
            "needsProvider=$needsProvider, state=$state, blocker=$blocker, hashes=redacted)"
}

class ChapterEditRebuildPlan internal constructor(
    internal val request: ChapterEditRebuildPlanRequest,
    val planSchemaVersion: Int,
    val policyVersion: String,
    val editedChapterIndex: Int,
    val highestCommittedChapterIndex: Int,
    val hasLaterCommittedChapters: Boolean,
    val laterCommittedChapterCount: Int,
    val futureChapterPolicy: FutureChapterPolicy,
    val laterBodiesRetained: Boolean,
    val frozenChapters: List<ChapterEditRebuildFrozenChapter>,
    val steps: List<ChapterEditRebuildStep>,
    val planHash: String,
) {
    val providerStepCount: Int = steps.count(ChapterEditRebuildStep::needsProvider)
    val readyStepCount: Int = steps.count { it.state == ChapterEditRebuildStepState.READY }
    val waitingStepCount: Int = steps.count { it.state == ChapterEditRebuildStepState.WAITING_FOR_DEPENDENCY }
    val satisfiedStepCount: Int = steps.count { it.state == ChapterEditRebuildStepState.ALREADY_SATISFIED }
    val blockedStepCount: Int = steps.count { it.state == ChapterEditRebuildStepState.BLOCKED }

    override fun toString(): String =
        "ChapterEditRebuildPlan(editedChapterIndex=$editedChapterIndex, " +
            "highestCommittedChapterIndex=$highestCommittedChapterIndex, " +
            "hasLaterCommittedChapters=$hasLaterCommittedChapters, " +
            "laterCommittedChapterCount=$laterCommittedChapterCount, policy=$futureChapterPolicy, " +
            "stepCounts=${listOf(readyStepCount, waitingStepCount, satisfiedStepCount, blockedStepCount)}, " +
            "providerStepCount=$providerStepCount, identifiers=redacted, hashes=redacted)"
}

/**
 * Builds a read-only, deterministic impact plan after a user edit.
 *
 * This boundary never writes the database, creates generation work, opens a Provider, estimates cost,
 * clones later versions, or overwrites stale history. A BLOCKED step is an explicit data-model fact,
 * not permission for an executor to bypass the existing uniqueness or chapter-order guards.
 */
class ChapterEditRebuildPlanRepository(private val database: ZhijuanDatabase) {
    suspend fun plan(request: ChapterEditRebuildPlanRequest): ChapterEditRebuildPlan =
        database.withTransaction { buildPlan(request) }

    suspend fun requireCurrentMatches(plan: ChapterEditRebuildPlan) {
        database.withTransaction { requireCurrentMatchesInTransaction(plan) }
    }

    internal suspend fun requireCurrentMatchesInTransaction(plan: ChapterEditRebuildPlan) {
        val current = buildPlan(plan.request)
        require(current.planHash == plan.planHash) {
            "Chapter rebuild plan no longer matches the current book state."
        }
    }

    private suspend fun buildPlan(request: ChapterEditRebuildPlanRequest): ChapterEditRebuildPlan {
        require(request.futureChapterPolicy == FutureChapterPolicy.KEEP_EXISTING) {
            "Regenerating later chapters is not available in this rebuild-planning phase."
        }
        val library = database.libraryDao()
        val memory = database.memoryDao()
        val book = requireNotNull(library.findBook(request.bookId)) { "Book does not exist." }
        require(book.archivedAt == null && book.deletedAt == null) {
            "Archived or deleted books cannot accept rebuild planning."
        }
        val editedChapter = requireNotNull(library.findChapter(request.editedChapterId)) {
            "Edited chapter does not exist."
        }
        require(editedChapter.bookId == book.bookId) { "Edited chapter belongs to another book." }
        val editedVersion = requireNotNull(library.findChapterVersion(request.editedVersionId)) {
            "Edited chapter version does not exist."
        }
        require(
            editedVersion.chapterId == editedChapter.chapterId &&
                editedChapter.currentVersionId == editedVersion.chapterVersionId,
        ) { "Edited chapter version is no longer the current version for this chapter." }
        require(
            editedVersion.source == ChapterVersionSource.USER_EDIT &&
                editedChapter.status == ChapterStatus.EDITED &&
                editedChapter.consistencyStatus == ConsistencyStatus.UNKNOWN,
        ) { "Rebuild planning requires the current unresolved USER_EDIT version." }
        val parentId = requireNotNull(editedVersion.parentVersionId) {
            "Edited chapter version must preserve its replaced parent."
        }
        require(library.findChapterVersion(parentId)?.chapterId == editedChapter.chapterId) {
            "Edited chapter parent is missing or belongs to another chapter."
        }

        val committed = library.currentChapterVersionSnapshotsForBook(book.bookId)
        val affected = committed.filter { it.chapterIndex >= editedChapter.chapterIndex }
        require(affected.firstOrNull()?.chapterId == editedChapter.chapterId) {
            "Edited chapter is not the first committed chapter in the affected range."
        }
        val frozen = affected.map { snapshot ->
            require(HASH.matches(snapshot.contentHash)) { "A committed chapter version has invalid provenance." }
            ChapterEditRebuildFrozenChapter(
                chapterIndex = snapshot.chapterIndex,
                chapterIdHash = sha256Utf8(snapshot.chapterId),
                currentVersionIdHash = sha256Utf8(snapshot.chapterVersionId),
                contentHash = snapshot.contentHash,
                status = snapshot.chapterStatus,
                consistencyStatus = snapshot.consistencyStatus,
            )
        }
        val trackingByVersion = memory.validTrackingProjectionsFromChapter(book.bookId, editedChapter.chapterIndex)
            .associateBy { it.chapterVersionId }
        val authorizedRetainedTrackingProjectionIds = ChapterEditRebuildStageRepository(database)
            .authorizedRetainedTrackingProjectionIdsForPlan(request, trackingByVersion)
        val aggregateByIndex = memory.validAggregateStatesFromChapter(book.bookId, editedChapter.chapterIndex)
            .associateBy { it.throughChapterIndex }
        val laterCount = (affected.size - 1).coerceAtLeast(0)
        val stepDrafts = mutableListOf<StepDraft>()
        fun add(
            type: ChapterEditRebuildStepType,
            snapshot: CurrentChapterVersionSnapshot,
            needsProvider: Boolean,
            state: ChapterEditRebuildStepState,
            blocker: ChapterEditRebuildBlocker = ChapterEditRebuildBlocker.NONE,
            dependencies: List<Int> = emptyList(),
        ): Int {
            val ordinal = stepDrafts.size + 1
            stepDrafts += StepDraft(
                ordinal = ordinal,
                type = type,
                chapterIndex = snapshot.chapterIndex,
                sourceVersionIdHash = sha256Utf8(snapshot.chapterVersionId),
                sourceContentHash = snapshot.contentHash,
                needsProvider = needsProvider,
                state = state,
                blocker = blocker,
                dependsOnOrdinals = dependencies,
            )
            return ordinal
        }

        val editedSummary = memory.findSummaryForVersion(editedVersion.chapterVersionId)
        val memoryState = when {
            editedSummary == null -> ChapterEditRebuildStepState.READY
            editedSummary.status == DerivedDataStatus.VALID -> ChapterEditRebuildStepState.ALREADY_SATISFIED
            else -> ChapterEditRebuildStepState.BLOCKED
        }
        val memoryOrdinal = add(
            type = ChapterEditRebuildStepType.EXTRACT_EDITED_MEMORY,
            snapshot = affected.first(),
            needsProvider = true,
            state = memoryState,
            blocker = if (memoryState == ChapterEditRebuildStepState.BLOCKED) {
                ChapterEditRebuildBlocker.DERIVED_VERSION_SLOT_OCCUPIED
            } else {
                ChapterEditRebuildBlocker.NONE
            },
        )

        var previousTrackingOrdinal: Int? = null
        var previousAggregateOrdinal: Int? = null
        affected.forEachIndexed { affectedOffset, snapshot ->
            val isEdited = affectedOffset == 0
            val trackingDependencies = listOfNotNull(if (isEdited) memoryOrdinal else previousTrackingOrdinal)
            val projection = trackingByVersion[snapshot.chapterVersionId]
            val trackingStateAndBlocker = when {
                isEdited && projection?.status == DerivedDataStatus.VALID ->
                    ChapterEditRebuildStepState.ALREADY_SATISFIED to ChapterEditRebuildBlocker.NONE
                !isEdited && projection != null &&
                    projection.projectionId in authorizedRetainedTrackingProjectionIds ->
                    ChapterEditRebuildStepState.ALREADY_SATISFIED to ChapterEditRebuildBlocker.NONE
                projection != null ->
                    ChapterEditRebuildStepState.BLOCKED to ChapterEditRebuildBlocker.DERIVED_VERSION_SLOT_OCCUPIED
                isEdited && laterCount > 0 ->
                    ChapterEditRebuildStepState.BLOCKED to ChapterEditRebuildBlocker.TRACKING_ORDER_GUARD
                !isEdited && affectedOffset < affected.lastIndex ->
                    ChapterEditRebuildStepState.BLOCKED to ChapterEditRebuildBlocker.TRACKING_ORDER_GUARD
                dependenciesBlocked(trackingDependencies, stepDrafts) ->
                    ChapterEditRebuildStepState.BLOCKED to ChapterEditRebuildBlocker.DEPENDENCY_BLOCKED
                dependenciesSatisfied(trackingDependencies, stepDrafts) ->
                    ChapterEditRebuildStepState.READY to ChapterEditRebuildBlocker.NONE
                else -> ChapterEditRebuildStepState.WAITING_FOR_DEPENDENCY to ChapterEditRebuildBlocker.NONE
            }
            val trackingOrdinal = add(
                type = ChapterEditRebuildStepType.REBUILD_STORY_TRACKING,
                snapshot = snapshot,
                needsProvider = true,
                state = trackingStateAndBlocker.first,
                blocker = trackingStateAndBlocker.second,
                dependencies = trackingDependencies,
            )

            val contextOrdinal = if (!isEdited) {
                val dependencies = listOfNotNull(previousAggregateOrdinal, trackingOrdinal)
                val stateAndBlocker = dependencyState(dependencies, stepDrafts)
                add(
                    type = ChapterEditRebuildStepType.REASSEMBLE_CONTEXT,
                    snapshot = snapshot,
                    needsProvider = false,
                    state = stateAndBlocker.first,
                    blocker = stateAndBlocker.second,
                    dependencies = dependencies,
                )
            } else {
                null
            }

            val consistencyDependencies = listOfNotNull(trackingOrdinal, contextOrdinal)
            val consistencyStateAndBlocker = dependencyState(consistencyDependencies, stepDrafts)
            add(
                type = ChapterEditRebuildStepType.RECHECK_CONSISTENCY,
                snapshot = snapshot,
                needsProvider = true,
                state = consistencyStateAndBlocker.first,
                blocker = consistencyStateAndBlocker.second,
                dependencies = consistencyDependencies,
            )

            val aggregateDependencies = listOfNotNull(previousAggregateOrdinal, trackingOrdinal)
            val aggregate = aggregateByIndex[snapshot.chapterIndex]
            val aggregateMatches = aggregate?.let { stored ->
                runCatching {
                    val decoded = AggregateStateSnapshotCodecV1.decodeAndVerify(
                        json = stored.stateJson,
                        expectedHash = stored.contentHash,
                    )
                    stored.schemaVersion == AggregateStateSnapshotCodecV1.SCHEMA_VERSION &&
                        stored.sourceThroughChapterVersionId == snapshot.chapterVersionId &&
                        decoded.bookId == book.bookId &&
                        decoded.throughChapterIndex == snapshot.chapterIndex &&
                        decoded.sourceChapterVersionId == snapshot.chapterVersionId &&
                        decoded.sourceChapterContentHash == snapshot.contentHash &&
                        projection != null &&
                        decoded.sourceTrackingProjectionId == projection.projectionId &&
                        decoded.sourceTrackingStageId == projection.generationStageId &&
                        decoded.sourceMemorySnapshotHash == projection.sourceMemorySnapshotHash &&
                        decoded.priorForeshadowSnapshotHash == projection.priorForeshadowSnapshotHash &&
                        decoded.sourceTrackingOutputHash == projection.outputContentHash &&
                        decoded.sourceTrackingPayloadHash == projection.payloadHash
                }.getOrDefault(false)
            } ?: false
            val aggregateStateAndBlocker = when {
                aggregateMatches ->
                    ChapterEditRebuildStepState.ALREADY_SATISFIED to ChapterEditRebuildBlocker.NONE
                aggregate != null ->
                    ChapterEditRebuildStepState.BLOCKED to ChapterEditRebuildBlocker.DERIVED_VERSION_SLOT_OCCUPIED
                else -> dependencyState(aggregateDependencies, stepDrafts)
            }
            val aggregateOrdinal = add(
                type = ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE,
                snapshot = snapshot,
                needsProvider = false,
                state = aggregateStateAndBlocker.first,
                blocker = aggregateStateAndBlocker.second,
                dependencies = aggregateDependencies,
            )
            previousTrackingOrdinal = trackingOrdinal
            previousAggregateOrdinal = aggregateOrdinal
        }

        val steps = stepDrafts.map(StepDraft::toPublic)
        val highestCommittedIndex = committed.maxOfOrNull(CurrentChapterVersionSnapshot::chapterIndex)
            ?: editedChapter.chapterIndex
        val planHash = planHash(
            request = request,
            editedChapterIndex = editedChapter.chapterIndex,
            highestCommittedChapterIndex = highestCommittedIndex,
            frozenChapters = frozen,
            steps = steps,
        )
        return ChapterEditRebuildPlan(
            request = request,
            planSchemaVersion = PLAN_SCHEMA_VERSION,
            policyVersion = POLICY_VERSION,
            editedChapterIndex = editedChapter.chapterIndex,
            highestCommittedChapterIndex = highestCommittedIndex,
            hasLaterCommittedChapters = laterCount > 0,
            laterCommittedChapterCount = laterCount,
            futureChapterPolicy = request.futureChapterPolicy,
            laterBodiesRetained = true,
            frozenChapters = frozen,
            steps = steps,
            planHash = planHash,
        )
    }

    private fun dependencyState(
        dependencies: List<Int>,
        drafts: List<StepDraft>,
    ): Pair<ChapterEditRebuildStepState, ChapterEditRebuildBlocker> = when {
        dependenciesBlocked(dependencies, drafts) ->
            ChapterEditRebuildStepState.BLOCKED to ChapterEditRebuildBlocker.DEPENDENCY_BLOCKED
        dependenciesSatisfied(dependencies, drafts) ->
            ChapterEditRebuildStepState.READY to ChapterEditRebuildBlocker.NONE
        else -> ChapterEditRebuildStepState.WAITING_FOR_DEPENDENCY to ChapterEditRebuildBlocker.NONE
    }

    private fun dependenciesBlocked(dependencies: List<Int>, drafts: List<StepDraft>): Boolean =
        dependencies.any { ordinal -> drafts[ordinal - 1].state == ChapterEditRebuildStepState.BLOCKED }

    private fun dependenciesSatisfied(dependencies: List<Int>, drafts: List<StepDraft>): Boolean =
        dependencies.all { ordinal ->
            drafts[ordinal - 1].state == ChapterEditRebuildStepState.ALREADY_SATISFIED
        }

    private fun planHash(
        request: ChapterEditRebuildPlanRequest,
        editedChapterIndex: Int,
        highestCommittedChapterIndex: Int,
        frozenChapters: List<ChapterEditRebuildFrozenChapter>,
        steps: List<ChapterEditRebuildStep>,
    ): String = stableHash(
        POLICY_VERSION,
        PLAN_SCHEMA_VERSION,
        request.bookId,
        request.editedChapterId,
        request.editedVersionId,
        request.futureChapterPolicy.name,
        editedChapterIndex,
        highestCommittedChapterIndex,
        frozenChapters.map { frozen ->
            listOf(
                frozen.chapterIndex,
                frozen.chapterIdHash,
                frozen.currentVersionIdHash,
                frozen.contentHash,
                frozen.status.name,
                frozen.consistencyStatus.name,
            )
        },
        steps.map { step ->
            listOf(
                step.ordinal,
                step.type.name,
                step.chapterIndex,
                step.sourceVersionIdHash,
                step.sourceContentHash,
                step.needsProvider,
                step.state.name,
                step.blocker.name,
                step.dependsOnOrdinals,
            )
        },
    )

    private data class StepDraft(
        val ordinal: Int,
        val type: ChapterEditRebuildStepType,
        val chapterIndex: Int,
        val sourceVersionIdHash: String,
        val sourceContentHash: String,
        val needsProvider: Boolean,
        val state: ChapterEditRebuildStepState,
        val blocker: ChapterEditRebuildBlocker,
        val dependsOnOrdinals: List<Int>,
    ) {
        fun toPublic() = ChapterEditRebuildStep(
            ordinal = ordinal,
            type = type,
            chapterIndex = chapterIndex,
            sourceVersionIdHash = sourceVersionIdHash,
            sourceContentHash = sourceContentHash,
            needsProvider = needsProvider,
            state = state,
            blocker = blocker,
            dependsOnOrdinals = dependsOnOrdinals,
        )
    }

    private companion object {
        const val PLAN_SCHEMA_VERSION = 2
        const val POLICY_VERSION = "zhijuan.chapter-edit-rebuild-plan.v2"
    }
}

private fun stableHash(vararg values: Any?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: Any?) {
        when (value) {
            is Iterable<*> -> {
                digest.update(2.toByte())
                value.forEach(::add)
                digest.update(3.toByte())
            }
            else -> {
                val bytes = (value?.toString() ?: "<null>").toByteArray(Charsets.UTF_8)
                try {
                    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                    digest.update(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
        }
    }
    values.forEach(::add)
    return digest.digest().toHex()
}

private fun sha256Utf8(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return try {
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    } finally {
        bytes.fill(0)
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
