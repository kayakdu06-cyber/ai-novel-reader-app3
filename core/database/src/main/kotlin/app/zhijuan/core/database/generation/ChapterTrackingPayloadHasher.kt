package app.zhijuan.core.database.generation

import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.ForeshadowTransitionEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.model.ForeshadowStatus
import java.nio.ByteBuffer
import java.security.MessageDigest

data class ForeshadowProjectionUpdate(
    val foreshadowItemId: String,
    val expectedFromStatus: ForeshadowStatus,
    val toStatus: ForeshadowStatus,
    val visibleEntityIdsJson: String,
    val importance: Int,
    val resolvedChapterVersionId: String?,
)

object ChapterTrackingPayloadHasher {
    fun hash(
        timeline: List<TimelineEventEntity>,
        newForeshadows: List<ForeshadowItemEntity>,
        updates: List<ForeshadowProjectionUpdate>,
        transitions: List<ForeshadowTransitionEntity>,
    ): String = stableHash(
        "zhijuan.chapter-tracking-payload.v1",
        timeline.sortedBy { it.timelineEventId }.map { event ->
            listOf(
                event.timelineEventId, event.bookId, event.name, event.participantsJson, event.locationEntityId,
                event.storyTimeExpression, event.storyOrder, event.constraintsJson, event.sourceChapterVersionId,
                event.status.name, event.createdAt,
            )
        },
        newForeshadows.sortedBy { it.foreshadowItemId }.map { item ->
            listOf(
                item.foreshadowItemId, item.bookId, item.description, item.foreshadowStatus.name,
                item.memoryStatus.name, item.targetStartChapterIndex, item.targetEndChapterIndex,
                item.sourceChapterVersionId, item.plantedChapterVersionId, item.resolvedChapterVersionId,
                item.visibleEntityIdsJson, item.importance, item.source.name, item.createdAt, item.updatedAt,
            )
        },
        updates.sortedBy { it.foreshadowItemId }.map { update ->
            listOf(
                update.foreshadowItemId, update.expectedFromStatus.name, update.toStatus.name,
                update.visibleEntityIdsJson, update.importance, update.resolvedChapterVersionId,
            )
        },
        transitions.sortedBy { it.transitionId }.map { transition ->
            listOf(
                transition.transitionId, transition.foreshadowItemId, transition.bookId,
                transition.sourceChapterVersionId, transition.generationStageId, transition.storyOrder,
                transition.operation, transition.fromStatus?.name, transition.toStatus.name,
                transition.evidenceJson, transition.status.name, transition.createdAt,
            )
        },
    )

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
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
