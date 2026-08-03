package app.zhijuan.feature.generation

import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.ForeshadowTransitionEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.database.generation.ForeshadowProjectionUpdate
import app.zhijuan.core.database.generation.ChapterTrackingPayloadHasher
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.MemorySource
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ChapterTrackingProjectionMappingSpec(
    val bookId: String,
    val generationStageId: String,
    val modelSnapshotJson: String,
    val createdAt: Long,
) {
    init {
        require(IDENTIFIER.matches(bookId) && IDENTIFIER.matches(generationStageId))
        require(modelSnapshotJson.isNotBlank() && modelSnapshotJson.length <= 65_536)
        require(createdAt >= 0L)
    }
}

data class ChapterTrackingProjectionDerivedDraft(
    val projection: ChapterTrackingProjectionEntity,
    val timelineEvents: List<TimelineEventEntity>,
    val newForeshadows: List<ForeshadowItemEntity>,
    val existingForeshadowUpdates: List<ForeshadowProjectionUpdate>,
    val foreshadowTransitions: List<ForeshadowTransitionEntity>,
    val trackingContentHash: String,
) {
    override fun toString(): String =
        "ChapterTrackingProjectionDerivedDraft(timelineCount=${timelineEvents.size}, " +
            "newForeshadowCount=${newForeshadows.size}, transitionCount=${foreshadowTransitions.size}, content=redacted)"
}

object ChapterTrackingProjectionPersistenceMapper {
    fun map(
        tracking: ChapterStoryTrackingV1,
        spec: ChapterTrackingProjectionMappingSpec,
    ): ChapterTrackingProjectionDerivedDraft {
        val baseOrder = Math.multiplyExact(tracking.chapterIndex.toLong(), STORY_ORDER_STRIDE)
        val timeline = tracking.timelineEvents.mapIndexed { index, event ->
            TimelineEventEntity(
                timelineEventId = stableId("timeline", spec.generationStageId, tracking.sourceChapterVersionId, index.toString()),
                bookId = spec.bookId,
                name = event.name,
                participantsJson = JsonArray(event.participantEntityIds.sorted().map(::JsonPrimitive)).toString(),
                locationEntityId = event.locationEntityId,
                storyTimeExpression = event.storyTimeExpression,
                storyOrder = Math.addExact(baseOrder, index.toLong() + 1L),
                constraintsJson = JsonObject(
                    linkedMapOf(
                        "schemaVersion" to JsonPrimitive(1),
                        "sourceChapterContentHash" to JsonPrimitive(tracking.sourceChapterContentHash),
                        "constraints" to JsonArray(event.constraints.map(::JsonPrimitive)),
                        "evidence" to JsonPrimitive(event.evidence),
                    ),
                ).toString(),
                sourceChapterVersionId = tracking.sourceChapterVersionId,
                status = DerivedDataStatus.VALID,
                createdAt = spec.createdAt,
            )
        }
        val newForeshadows = mutableListOf<ForeshadowItemEntity>()
        val updates = mutableListOf<ForeshadowProjectionUpdate>()
        val transitions = tracking.foreshadowOperations.mapIndexed { index, operation ->
            val itemId = operation.foreshadowItemId ?: stableId(
                "foreshadow",
                spec.generationStageId,
                tracking.sourceChapterVersionId,
                index.toString(),
            )
            val toStatus = operation.toStatus()
            val visibleJson = JsonArray(operation.visibleEntityIds.sorted().map(::JsonPrimitive)).toString()
            if (operation.operation == ForeshadowOperationV1.PLANT) {
                newForeshadows += ForeshadowItemEntity(
                    foreshadowItemId = itemId,
                    bookId = spec.bookId,
                    description = operation.description,
                    foreshadowStatus = ForeshadowStatus.PLANTED,
                    memoryStatus = DerivedDataStatus.VALID,
                    targetStartChapterIndex = operation.targetStartChapterIndex,
                    targetEndChapterIndex = operation.targetEndChapterIndex,
                    sourceChapterVersionId = tracking.sourceChapterVersionId,
                    plantedChapterVersionId = tracking.sourceChapterVersionId,
                    resolvedChapterVersionId = null,
                    visibleEntityIdsJson = visibleJson,
                    importance = operation.importance,
                    source = MemorySource.CHAPTER_EXTRACTION,
                    createdAt = spec.createdAt,
                    updatedAt = spec.createdAt,
                )
            } else {
                updates += ForeshadowProjectionUpdate(
                    foreshadowItemId = itemId,
                    expectedFromStatus = requireNotNull(operation.fromStatus),
                    toStatus = toStatus,
                    visibleEntityIdsJson = visibleJson,
                    importance = operation.importance,
                    resolvedChapterVersionId = tracking.sourceChapterVersionId.takeIf { toStatus == ForeshadowStatus.RESOLVED },
                )
            }
            ForeshadowTransitionEntity(
                transitionId = stableId("foreshadow-transition", spec.generationStageId, tracking.sourceChapterVersionId, index.toString()),
                foreshadowItemId = itemId,
                bookId = spec.bookId,
                sourceChapterVersionId = tracking.sourceChapterVersionId,
                generationStageId = spec.generationStageId,
                storyOrder = Math.addExact(baseOrder, FORESHADOW_ORDER_OFFSET + index.toLong()),
                operation = operation.operation.name,
                fromStatus = operation.fromStatus,
                toStatus = toStatus,
                evidenceJson = JsonObject(
                    linkedMapOf(
                        "schemaVersion" to JsonPrimitive(1),
                        "sourceChapterContentHash" to JsonPrimitive(tracking.sourceChapterContentHash),
                        "confidenceMicros" to JsonPrimitive(operation.confidenceMicros),
                        "evidence" to JsonPrimitive(operation.evidence),
                    ),
                ).toString(),
                status = DerivedDataStatus.VALID,
                createdAt = spec.createdAt,
            )
        }
        val payloadHash = ChapterTrackingPayloadHasher.hash(timeline, newForeshadows, updates, transitions)
        val projection = ChapterTrackingProjectionEntity(
            projectionId = stableId("tracking-projection", spec.generationStageId, tracking.sourceChapterVersionId),
            bookId = spec.bookId,
            chapterVersionId = tracking.sourceChapterVersionId,
            chapterIndex = tracking.chapterIndex,
            generationStageId = spec.generationStageId,
            sourceChapterContentHash = tracking.sourceChapterContentHash,
            sourceMemorySnapshotHash = tracking.memorySnapshotHash,
            priorForeshadowSnapshotHash = tracking.priorForeshadowSnapshotHash,
            outputContentHash = tracking.contentHash,
            payloadHash = payloadHash,
            status = DerivedDataStatus.VALID,
            modelSnapshotJson = spec.modelSnapshotJson,
            timelineEventCount = timeline.size,
            foreshadowTransitionCount = transitions.size,
            createdAt = spec.createdAt,
            updatedAt = spec.createdAt,
        )
        return ChapterTrackingProjectionDerivedDraft(
            projection = projection,
            timelineEvents = timeline,
            newForeshadows = newForeshadows,
            existingForeshadowUpdates = updates,
            foreshadowTransitions = transitions,
            trackingContentHash = tracking.contentHash,
        )
    }

    private fun ChapterForeshadowOperationV1.toStatus(): ForeshadowStatus = when (operation) {
        ForeshadowOperationV1.PLANT -> ForeshadowStatus.PLANTED
        ForeshadowOperationV1.DEVELOP -> ForeshadowStatus.DEVELOPING
        ForeshadowOperationV1.RESOLVE -> ForeshadowStatus.RESOLVED
        ForeshadowOperationV1.ABANDON -> ForeshadowStatus.ABANDONED
    }

    private fun stableId(prefix: String, vararg parts: String): String =
        "tracking.$prefix.${sha256(parts.joinToString("\u0000")).take(32)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private const val STORY_ORDER_STRIDE = 1_000_000L
    private const val FORESHADOW_ORDER_OFFSET = 100_000L
}

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
