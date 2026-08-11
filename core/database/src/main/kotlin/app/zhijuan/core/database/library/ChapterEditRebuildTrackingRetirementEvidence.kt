package app.zhijuan.core.database.library

import app.zhijuan.core.database.memory.TimelineEventEntity
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object ChapterEditRebuildTrackingRetirementEvidenceV1 {
    const val POLICY_VERSION = "zhijuan.chapter-edit-rebuild-tracking-retirement.v1"
    const val MAX_TIMELINE_EVENTS = 64
    private const val FINGERPRINT_POLICY = "zhijuan.chapter-edit-rebuild-retired-timeline.v1"
    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val STRICT_JSON = Json { isLenient = false }

    fun encodeTimelineIds(events: List<TimelineEventEntity>): String {
        val ids = normalized(events).map(TimelineEventEntity::timelineEventId)
        return JsonArray(ids.map(::JsonPrimitive)).toString()
    }

    fun decodeTimelineIds(value: String): List<String> {
        require(value.toByteArray(Charsets.UTF_8).size in 2..16_384) {
            "Retired timeline identity evidence has an invalid size."
        }
        val array = runCatching { STRICT_JSON.parseToJsonElement(value) as JsonArray }
            .getOrElse { throw IllegalArgumentException("Retired timeline identity evidence is invalid JSON.") }
        require(array.size <= MAX_TIMELINE_EVENTS)
        val ids = array.map { element ->
            (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?: throw IllegalArgumentException("Retired timeline identity evidence contains a non-string value.")
        }
        require(ids.all(IDENTIFIER::matches) && ids == ids.sorted() && ids.distinct().size == ids.size) {
            "Retired timeline identities are invalid, duplicated, or unordered."
        }
        return ids
    }

    fun fingerprint(events: List<TimelineEventEntity>): String = stableHash(
        FINGERPRINT_POLICY,
        normalized(events).map { event ->
            listOf(
                event.timelineEventId,
                event.bookId,
                event.name,
                event.participantsJson,
                event.locationEntityId,
                event.storyTimeExpression,
                event.storyOrder,
                event.constraintsJson,
                event.sourceChapterVersionId,
                event.createdAt,
            )
        },
    )

    private fun normalized(events: List<TimelineEventEntity>): List<TimelineEventEntity> {
        require(events.size <= MAX_TIMELINE_EVENTS)
        require(events.map(TimelineEventEntity::timelineEventId).all(IDENTIFIER::matches))
        require(events.map(TimelineEventEntity::timelineEventId).distinct().size == events.size)
        return events.sortedBy(TimelineEventEntity::timelineEventId)
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
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
