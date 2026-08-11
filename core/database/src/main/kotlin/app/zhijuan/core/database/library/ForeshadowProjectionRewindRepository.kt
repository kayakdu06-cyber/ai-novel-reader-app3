package app.zhijuan.core.database.library

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.ForeshadowProjectionRevisionWriterV1
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.ForeshadowProjectionRevisionEntity
import app.zhijuan.core.database.memory.ForeshadowProjectionRewindEntity
import app.zhijuan.core.database.memory.ForeshadowTransitionEntity
import app.zhijuan.core.database.memory.MemoryDao
import app.zhijuan.core.database.search.MemorySearchIndexWriterV1
import app.zhijuan.core.database.search.MemorySearchSourceIdentityV1
import app.zhijuan.core.database.search.MemorySearchSourceTypeV1
import app.zhijuan.core.model.DerivedDataStatus
import java.nio.ByteBuffer
import java.security.MessageDigest

data class ForeshadowProjectionRewindCommand(
    val plan: ChapterEditRebuildPlan,
    val rewindId: String,
    val rewoundAt: Long,
) {
    override fun toString(): String =
        "ForeshadowProjectionRewindCommand(rewoundAt=$rewoundAt, identifiers=redacted, plan=redacted)"
}

class ForeshadowProjectionRewindResult internal constructor(
    val rewindId: String,
    val firstAffectedChapterIndex: Int,
    val lastAffectedChapterIndex: Int,
    val affectedItemCount: Int,
    val baselineItemCount: Int,
    val absentItemCount: Int,
    val staleRevisionCount: Int,
    val staleTransitionCount: Int,
    val replayed: Boolean,
) {
    override fun toString(): String =
        "ForeshadowProjectionRewindResult(range=$firstAffectedChapterIndex..$lastAffectedChapterIndex, " +
            "counts=${listOf(affectedItemCount, baselineItemCount, absentItemCount)}, replayed=$replayed, " +
            "identifiers=redacted)"
}

/**
 * Rewinds only the mutable foreshadow projection to the last trusted state before an edited chapter.
 * History is retained and made stale in revision-before-transition order. This special audited path does
 * not relax the normal forward transition state machine and never fabricates missing legacy checkpoints.
 */
class ForeshadowProjectionRewindRepository(private val database: ZhijuanDatabase) {
    suspend fun rewind(command: ForeshadowProjectionRewindCommand): ForeshadowProjectionRewindResult {
        validate(command)
        return database.withTransaction {
            val plan = command.plan
            ChapterEditRebuildPlanRepository(database).requireCurrentMatchesInTransaction(plan)
            val memory = database.memoryDao()
            val library = database.libraryDao()
            val request = plan.request
            val editedVersion = requireNotNull(library.findChapterVersion(request.editedVersionId)) {
                "Edited chapter version does not exist."
            }
            val replacedVersionId = requireNotNull(editedVersion.parentVersionId) {
                "Edited chapter version does not preserve its replaced parent."
            }
            require(
                editedVersion.chapterId == request.editedChapterId &&
                    command.rewoundAt >= editedVersion.createdAt,
            ) { "Foreshadow rewind binding is invalid." }

            val existing = memory.findForeshadowProjectionRewind(command.rewindId)
            if (existing != null) {
                return@withTransaction requireExactReplay(command, existing, memory)
            }
            require(memory.findForeshadowProjectionRewindForPlan(plan.planHash) == null) {
                "This rebuild plan is already bound to another foreshadow rewind."
            }

            val range = affectedHistory(memory, plan)
            val current = currentItems(memory, request.bookId, range.itemIds)
            require(current.all { command.rewoundAt >= it.updatedAt }) {
                "Foreshadow rewind time precedes an affected projection."
            }
            val baselines = trustedBaselines(memory, request.bookId, plan.editedChapterIndex, range)
            val beforeHash = projectionSetHash(current)
            val baselineHash = trustedBaselineSetHash(range.itemIds, baselines)

            val staleRevisionCount = memory.staleForeshadowProjectionRevisionsForChapterRange(
                request.bookId,
                plan.editedChapterIndex,
                plan.highestCommittedChapterIndex,
            )
            val staleTransitionCount = memory.staleForeshadowTransitionsForChapterRange(
                request.bookId,
                plan.editedChapterIndex,
                plan.highestCommittedChapterIndex,
            )
            requireRangeIsStale(memory, request.bookId, plan)

            val desired = current.map { item ->
                baselines[item.foreshadowItemId]?.item
                    ?: if (item.memoryStatus == DerivedDataStatus.STALE) {
                        item
                    } else {
                        item.copy(memoryStatus = DerivedDataStatus.STALE, updatedAt = command.rewoundAt)
                    }
            }
            current.zip(desired).forEach { (expected, target) ->
                if (expected != target) {
                    check(memory.compareAndSetForRewind(expected, target) == 1) {
                        "Foreshadow projection changed while rewind was committing."
                    }
                }
            }

            val search = database.memorySearchDao()
            search.deleteSources(
                range.itemIds.map { itemId ->
                    MemorySearchSourceIdentityV1(
                        bookId = request.bookId,
                        sourceType = MemorySearchSourceTypeV1.FORESHADOW,
                        sourceId = itemId,
                    )
                },
            )
            baselines.values.sortedBy { it.item.foreshadowItemId }.forEach { baseline ->
                MemorySearchIndexWriterV1.replaceStoryTracking(
                    search = search,
                    chapterIndex = baseline.revision.chapterIndex,
                    timelineEvents = emptyList(),
                    foreshadows = listOf(baseline.item),
                )
            }

            val after = currentItems(memory, request.bookId, range.itemIds)
            require(after == desired.sortedBy(ForeshadowItemEntity::foreshadowItemId)) {
                "Foreshadow rewind did not produce the trusted projection set."
            }
            val afterHash = projectionSetHash(after)
            val audit = ForeshadowProjectionRewindEntity(
                rewindId = command.rewindId,
                bookId = request.bookId,
                editedChapterId = request.editedChapterId,
                editedChapterVersionId = request.editedVersionId,
                replacedChapterVersionId = replacedVersionId,
                firstAffectedChapterIndex = plan.editedChapterIndex,
                lastAffectedChapterIndex = plan.highestCommittedChapterIndex,
                planHash = plan.planHash,
                beforeProjectionSetHash = beforeHash,
                trustedBaselineSetHash = baselineHash,
                afterProjectionSetHash = afterHash,
                affectedItemCount = range.itemIds.size,
                baselineItemCount = baselines.size,
                absentItemCount = range.itemIds.size - baselines.size,
                staleRevisionCount = staleRevisionCount,
                staleTransitionCount = staleTransitionCount,
                policyVersion = POLICY_VERSION,
                createdAt = command.rewoundAt,
            )
            memory.insertForeshadowProjectionRewind(audit)
            audit.toResult(replayed = false)
        }
    }

    private suspend fun requireExactReplay(
        command: ForeshadowProjectionRewindCommand,
        audit: ForeshadowProjectionRewindEntity,
        memory: MemoryDao,
    ): ForeshadowProjectionRewindResult {
        val plan = command.plan
        val request = plan.request
        require(
            audit.bookId == request.bookId &&
                audit.editedChapterId == request.editedChapterId &&
                audit.editedChapterVersionId == request.editedVersionId &&
                audit.firstAffectedChapterIndex == plan.editedChapterIndex &&
                audit.lastAffectedChapterIndex == plan.highestCommittedChapterIndex &&
                audit.planHash == plan.planHash && audit.policyVersion == POLICY_VERSION &&
                audit.createdAt == command.rewoundAt,
        ) { "Foreshadow rewind id is already bound to different provenance." }
        val range = affectedHistory(memory, plan)
        val baselines = trustedBaselines(memory, request.bookId, plan.editedChapterIndex, range)
        require(
            audit.affectedItemCount == range.itemIds.size &&
                audit.baselineItemCount == baselines.size &&
                audit.absentItemCount == range.itemIds.size - baselines.size &&
                audit.trustedBaselineSetHash == trustedBaselineSetHash(range.itemIds, baselines),
        ) { "Foreshadow rewind history no longer matches its audit record." }
        requireRangeIsStale(memory, request.bookId, plan)
        val current = currentItems(memory, request.bookId, range.itemIds)
        require(projectionSetHash(current) == audit.afterProjectionSetHash) {
            "Foreshadow projection changed after the audited rewind."
        }
        return audit.toResult(replayed = true)
    }

    private suspend fun affectedHistory(
        memory: MemoryDao,
        plan: ChapterEditRebuildPlan,
    ): AffectedHistory {
        val transitions = memory.foreshadowTransitionHistoryForChapterRange(
            plan.request.bookId,
            plan.editedChapterIndex,
            plan.highestCommittedChapterIndex,
        )
        require(transitions.size <= MAX_AFFECTED_TRANSITIONS) {
            "Foreshadow rewind exceeds its transition safety limit."
        }
        val itemIds = transitions.map(ForeshadowTransitionEntity::foreshadowItemId).distinct().sorted()
        require(itemIds.size <= MAX_AFFECTED_ITEMS) {
            "Foreshadow rewind exceeds its projection safety limit."
        }
        return AffectedHistory(transitions = transitions, itemIds = itemIds)
    }

    private suspend fun trustedBaselines(
        memory: MemoryDao,
        bookId: String,
        beforeChapterIndex: Int,
        range: AffectedHistory,
    ): Map<String, TrustedForeshadowBaseline> {
        if (range.itemIds.isEmpty()) return emptyMap()
        val revisions = memory.latestTrustedForeshadowProjectionRevisionsBeforeChapter(
            bookId = bookId,
            foreshadowItemIds = range.itemIds,
            beforeChapterIndex = beforeChapterIndex,
        )
        require(revisions.map(ForeshadowProjectionRevisionEntity::foreshadowItemId).distinct().size == revisions.size) {
            "Foreshadow rewind found multiple trusted baselines for one projection."
        }
        val transitions = if (revisions.isEmpty()) {
            emptyMap()
        } else {
            memory.foreshadowTransitionsByIds(revisions.map(ForeshadowProjectionRevisionEntity::transitionId))
                .associateBy(ForeshadowTransitionEntity::transitionId)
        }
        require(transitions.size == revisions.size) { "A trusted foreshadow baseline lost its transition." }
        val writer = ForeshadowProjectionRevisionWriterV1(memory)
        val baselines = revisions.associate { revision ->
            val transition = requireNotNull(transitions[revision.transitionId]) {
                "A trusted foreshadow baseline lost its transition."
            }
            require(revision.chapterIndex < beforeChapterIndex) {
                "Foreshadow baseline is not before the edited chapter."
            }
            revision.foreshadowItemId to TrustedForeshadowBaseline(
                revision = revision,
                item = writer.decodeAndVerifyStored(revision, transition),
            )
        }
        range.transitions.groupBy(ForeshadowTransitionEntity::foreshadowItemId).forEach { (itemId, history) ->
            if (itemId !in baselines) {
                val earliest = history.minWithOrNull(TRANSITION_ORDER)
                    ?: error("Affected foreshadow history is unexpectedly empty.")
                require(earliest.operation == "PLANT" && earliest.fromStatus == null) {
                    "Legacy foreshadow history has no trusted pre-edit baseline."
                }
            }
        }
        return baselines
    }

    private suspend fun currentItems(
        memory: MemoryDao,
        bookId: String,
        itemIds: List<String>,
    ): List<ForeshadowItemEntity> {
        if (itemIds.isEmpty()) return emptyList()
        val items = memory.foreshadowsByIds(bookId, itemIds)
        require(items.map(ForeshadowItemEntity::foreshadowItemId) == itemIds) {
            "Affected foreshadow projections are incomplete or cross-book."
        }
        return items
    }

    private suspend fun requireRangeIsStale(
        memory: MemoryDao,
        bookId: String,
        plan: ChapterEditRebuildPlan,
    ) {
        require(
            memory.validForeshadowProjectionRevisionCountForChapterRange(
                bookId,
                plan.editedChapterIndex,
                plan.highestCommittedChapterIndex,
            ) == 0 &&
                memory.validForeshadowTransitionCountForChapterRange(
                    bookId,
                    plan.editedChapterIndex,
                    plan.highestCommittedChapterIndex,
                ) == 0,
        ) { "Foreshadow history remains valid inside the rewound chapter range." }
    }

    private fun validate(command: ForeshadowProjectionRewindCommand) {
        require(IDENTIFIER.matches(command.rewindId) && command.rewoundAt >= 0L) {
            "Foreshadow rewind command is invalid."
        }
        require(HASH.matches(command.plan.planHash)) { "Foreshadow rewind plan hash is invalid." }
    }

    private data class AffectedHistory(
        val transitions: List<ForeshadowTransitionEntity>,
        val itemIds: List<String>,
    )

    private companion object {
        const val POLICY_VERSION = "zhijuan.foreshadow-projection-rewind.v1"
        const val MAX_AFFECTED_ITEMS = 512
        const val MAX_AFFECTED_TRANSITIONS = 10_000
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
        val TRANSITION_ORDER = compareBy<ForeshadowTransitionEntity>(
            ForeshadowTransitionEntity::storyOrder,
            ForeshadowTransitionEntity::createdAt,
            ForeshadowTransitionEntity::transitionId,
        )
    }
}

private data class TrustedForeshadowBaseline(
    val revision: ForeshadowProjectionRevisionEntity,
    val item: ForeshadowItemEntity,
)

private suspend fun MemoryDao.compareAndSetForRewind(
    expected: ForeshadowItemEntity,
    target: ForeshadowItemEntity,
): Int {
    require(expected.foreshadowItemId == target.foreshadowItemId && expected.bookId == target.bookId)
    return compareAndSetForeshadowProjectionForRewind(
        foreshadowItemId = expected.foreshadowItemId,
        bookId = expected.bookId,
        expectedDescription = expected.description,
        expectedForeshadowStatus = expected.foreshadowStatus.name,
        expectedMemoryStatus = expected.memoryStatus.name,
        expectedTargetStartChapterIndex = expected.targetStartChapterIndex,
        expectedTargetEndChapterIndex = expected.targetEndChapterIndex,
        expectedSourceChapterVersionId = expected.sourceChapterVersionId,
        expectedPlantedChapterVersionId = expected.plantedChapterVersionId,
        expectedResolvedChapterVersionId = expected.resolvedChapterVersionId,
        expectedVisibleEntityIdsJson = expected.visibleEntityIdsJson,
        expectedImportance = expected.importance,
        expectedSource = expected.source.name,
        expectedCreatedAt = expected.createdAt,
        expectedUpdatedAt = expected.updatedAt,
        newDescription = target.description,
        newForeshadowStatus = target.foreshadowStatus.name,
        newMemoryStatus = target.memoryStatus.name,
        newTargetStartChapterIndex = target.targetStartChapterIndex,
        newTargetEndChapterIndex = target.targetEndChapterIndex,
        newSourceChapterVersionId = target.sourceChapterVersionId,
        newPlantedChapterVersionId = target.plantedChapterVersionId,
        newResolvedChapterVersionId = target.resolvedChapterVersionId,
        newVisibleEntityIdsJson = target.visibleEntityIdsJson,
        newImportance = target.importance,
        newSource = target.source.name,
        newCreatedAt = target.createdAt,
        newUpdatedAt = target.updatedAt,
    )
}

private fun trustedBaselineSetHash(
    itemIds: List<String>,
    baselines: Map<String, TrustedForeshadowBaseline>,
): String = rewindStableHash(
    "foreshadow-trusted-baseline-set-v1",
    itemIds.map { itemId ->
        val baseline = baselines[itemId]
        if (baseline == null) {
            listOf(itemId, "ABSENT")
        } else {
            listOf(
                itemId,
                "BASELINE",
                baseline.revision.revisionId,
                baseline.revision.transitionId,
                baseline.revision.chapterIndex,
                baseline.revision.storyOrder,
                baseline.revision.snapshotHash,
            )
        }
    },
)

private fun projectionSetHash(items: List<ForeshadowItemEntity>): String = rewindStableHash(
    "foreshadow-projection-set-v1",
    items.sortedBy(ForeshadowItemEntity::foreshadowItemId).map { item ->
        listOf(
            item.foreshadowItemId,
            item.bookId,
            item.description,
            item.foreshadowStatus.name,
            item.memoryStatus.name,
            item.targetStartChapterIndex,
            item.targetEndChapterIndex,
            item.sourceChapterVersionId,
            item.plantedChapterVersionId,
            item.resolvedChapterVersionId,
            item.visibleEntityIdsJson,
            item.importance,
            item.source.name,
            item.createdAt,
            item.updatedAt,
        )
    },
)

private fun rewindStableHash(vararg values: Any?): String {
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
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun ForeshadowProjectionRewindEntity.toResult(replayed: Boolean) =
    ForeshadowProjectionRewindResult(
        rewindId = rewindId,
        firstAffectedChapterIndex = firstAffectedChapterIndex,
        lastAffectedChapterIndex = lastAffectedChapterIndex,
        affectedItemCount = affectedItemCount,
        baselineItemCount = baselineItemCount,
        absentItemCount = absentItemCount,
        staleRevisionCount = staleRevisionCount,
        staleTransitionCount = staleTransitionCount,
        replayed = replayed,
    )
