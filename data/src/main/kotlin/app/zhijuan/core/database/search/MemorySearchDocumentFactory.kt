package app.zhijuan.core.database.search

import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

internal enum class MemorySearchSourceTypeV1 {
    STORY_ENTITY,
    CHAPTER_SUMMARY,
    ENTITY_EVENT,
    CANON_FACT,
    TIMELINE_EVENT,
    FORESHADOW,
}

/**
 * Deterministically derives an encrypted-database search pointer from an authoritative memory row.
 * Only ASCII search tokens and source metadata leave this factory; source prose and JSON do not.
 */
internal object MemorySearchDocumentFactoryV1 {
    private const val DOCUMENT_ID_PREFIX = "memory_search_v1:"
    private const val CANONICAL_PAYLOAD_VERSION = "memory-search-canonical-v1"
    private const val MAX_ID_CHARS = 256
    private const val MAX_TEXT_FIELD_CHARS = 16 * 1024
    private const val MAX_JSON_CHARS = 64 * 1024
    private const val MAX_JSON_DEPTH = 64
    private const val MAX_LEAF_CHARS = 8 * 1024
    private const val MAX_LEAF_COUNT = 512
    private const val MAX_SEARCH_TEXT_CHARS = 256 * 1024
    private const val MAX_TERMS_CHARS = 256 * 1024
    private const val MAX_PAYLOAD_CHARS = 512 * 1024

    private val strictJson = Json { isLenient = false }
    private val hexCharacters = "0123456789abcdef".toCharArray()
    private val strictJsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun from(story: StoryEntity): MemorySearchDocumentEntity? {
        if (story.archivedAt != null) return null
        return build(
            sourceType = MemorySearchSourceTypeV1.STORY_ENTITY,
            sourceId = story.entityId,
            bookId = story.bookId,
            chapterIndex = null,
            storyOrder = null,
            importance = 100,
            updatedAt = story.updatedAt,
        ) {
            add(story.canonicalName)
            addJson(story.aliasesJson)
            addJson(story.stableDefinitionJson)
        }
    }

    fun from(summary: ChapterSummaryEntity): MemorySearchDocumentEntity? {
        if (summary.status != DerivedDataStatus.VALID) return null
        return build(
            sourceType = MemorySearchSourceTypeV1.CHAPTER_SUMMARY,
            sourceId = summary.chapterSummaryId,
            bookId = summary.bookId,
            chapterIndex = summary.chapterIndex,
            storyOrder = null,
            importance = summary.importance,
            updatedAt = summary.updatedAt,
        ) {
            addJson(summary.summaryJson)
        }
    }

    fun from(
        event: EntityEventEntity,
        chapterIndex: Int?,
    ): MemorySearchDocumentEntity? {
        if (event.status != DerivedDataStatus.VALID) return null
        require(event.confidenceMicros in 0..1_000_000) {
            "Entity-event confidence is outside the supported range."
        }
        return build(
            sourceType = MemorySearchSourceTypeV1.ENTITY_EVENT,
            sourceId = event.entityEventId,
            bookId = event.bookId,
            chapterIndex = chapterIndex,
            storyOrder = event.storyOrder,
            importance = event.confidenceMicros / 10_000,
            updatedAt = event.createdAt,
        ) {
            add(event.attributeKey)
            event.oldValueJson?.let(::addJson)
            addJson(event.newValueJson)
            addJson(event.evidenceJson)
            event.storyTimeExpression?.let(::add)
        }
    }

    fun from(
        fact: CanonFactEntity,
        chapterIndex: Int?,
    ): MemorySearchDocumentEntity? {
        if (fact.status != DerivedDataStatus.VALID) return null
        return build(
            sourceType = MemorySearchSourceTypeV1.CANON_FACT,
            sourceId = fact.canonFactId,
            bookId = fact.bookId,
            chapterIndex = chapterIndex,
            storyOrder = fact.validFromStoryOrder,
            importance = canonImportance(fact.canonLevel),
            updatedAt = fact.createdAt,
        ) {
            add(fact.factText)
            addJson(fact.factPayloadJson)
            addJson(fact.scopeJson)
        }
    }

    fun from(
        timeline: TimelineEventEntity,
        chapterIndex: Int?,
    ): MemorySearchDocumentEntity? {
        if (timeline.status != DerivedDataStatus.VALID) return null
        return build(
            sourceType = MemorySearchSourceTypeV1.TIMELINE_EVENT,
            sourceId = timeline.timelineEventId,
            bookId = timeline.bookId,
            chapterIndex = chapterIndex,
            storyOrder = timeline.storyOrder,
            importance = 70,
            updatedAt = timeline.createdAt,
        ) {
            add(timeline.name)
            add(timeline.storyTimeExpression)
            addJson(timeline.participantsJson)
            addJson(timeline.constraintsJson)
        }
    }

    fun from(
        foreshadow: ForeshadowItemEntity,
        chapterIndex: Int?,
    ): MemorySearchDocumentEntity? {
        if (foreshadow.memoryStatus != DerivedDataStatus.VALID) return null
        if (
            foreshadow.foreshadowStatus == ForeshadowStatus.RESOLVED ||
            foreshadow.foreshadowStatus == ForeshadowStatus.ABANDONED
        ) {
            return null
        }
        return build(
            sourceType = MemorySearchSourceTypeV1.FORESHADOW,
            sourceId = foreshadow.foreshadowItemId,
            bookId = foreshadow.bookId,
            chapterIndex = chapterIndex,
            storyOrder = null,
            importance = foreshadow.importance,
            updatedAt = foreshadow.updatedAt,
        ) {
            add(foreshadow.description)
        }
    }

    private fun build(
        sourceType: MemorySearchSourceTypeV1,
        sourceId: String,
        bookId: String,
        chapterIndex: Int?,
        storyOrder: Long?,
        importance: Int,
        updatedAt: Long,
        configure: SearchTextBuilder.() -> Unit,
    ): MemorySearchDocumentEntity? {
        require(bookId.isNotBlank() && bookId.length <= MAX_ID_CHARS) {
            "Search book identity is invalid."
        }
        require(sourceId.isNotBlank() && sourceId.length <= MAX_ID_CHARS) {
            "Search source identity is invalid."
        }
        require(chapterIndex == null || chapterIndex >= 1) {
            "Search chapter index is invalid."
        }
        require(storyOrder == null || storyOrder >= 0L) {
            "Search story order is invalid."
        }
        require(importance in 0..100) { "Search importance is outside the supported range." }
        require(updatedAt >= 0L) { "Search update time is invalid." }

        val searchText = SearchTextBuilder().apply(configure).build()
        if (searchText.isEmpty()) return null
        val searchTerms = SearchIndexText.indexTerms(searchText)
        if (searchTerms.isEmpty()) return null
        require(searchTerms.length <= MAX_TERMS_CHARS) { "Search terms exceed the size limit." }
        require(searchTerms.all { it.code < 128 }) { "Search terms contain unsupported characters." }

        val canonicalPayload = buildString {
            append(CANONICAL_PAYLOAD_VERSION).append('|')
            append(sourceType.name).append('|')
            append(chapterIndex ?: "").append('|')
            append(storyOrder ?: "").append('|')
            append(importance).append('|')
            append(searchText.length).append(':').append(searchText)
        }
        require(canonicalPayload.length <= MAX_PAYLOAD_CHARS) {
            "Canonical search payload exceeds the size limit."
        }

        return MemorySearchDocumentEntity(
            rowId = 0,
            documentId = documentId(bookId, sourceType, sourceId),
            bookId = bookId,
            sourceType = sourceType.name,
            sourceId = sourceId,
            chapterIndex = chapterIndex,
            storyOrder = storyOrder,
            importance = importance,
            sourceContentHash = sha256Hex(canonicalPayload),
            searchTerms = searchTerms,
            updatedAt = updatedAt,
        )
    }

    private fun documentId(
        bookId: String,
        sourceType: MemorySearchSourceTypeV1,
        sourceId: String,
    ): String = DOCUMENT_ID_PREFIX + sha256Hex(
        bookId + '\u0000' + sourceType.name + '\u0000' + sourceId,
    )

    private fun canonImportance(level: CanonLevel): Int = when (level) {
        CanonLevel.HARD_CANON -> 100
        CanonLevel.STORY_CANON -> 80
        CanonLevel.PLAN_ONLY -> 60
        CanonLevel.INFERRED -> 40
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(hexCharacters[value ushr 4])
                append(hexCharacters[value and 0x0F])
            }
        }
    }

    private class SearchTextBuilder {
        private val value = StringBuilder()
        private var leafCount = 0

        fun add(text: String) {
            if (text.isEmpty()) return
            require(text.length <= MAX_TEXT_FIELD_CHARS) { "Search text field exceeds the size limit." }
            appendLeaf(text, MAX_TEXT_FIELD_CHARS)
        }

        fun addJson(json: String) {
            require(json.length <= MAX_JSON_CHARS) { "Search JSON exceeds the size limit." }
            val root = try {
                strictJson.parseToJsonElement(json)
            } catch (_: Exception) {
                throw IllegalArgumentException("Search JSON is invalid.")
            }
            appendJson(root, depth = 0)
        }

        fun build(): String = value.toString()

        private fun appendJson(
            element: JsonElement,
            depth: Int,
        ) {
            require(depth <= MAX_JSON_DEPTH) { "Search JSON exceeds the nesting limit." }
            when (element) {
                JsonNull -> Unit
                is JsonPrimitive -> {
                    if (element.isString) {
                        appendLeaf(element.content, MAX_LEAF_CHARS)
                    } else {
                        require(
                            element.content == "true" ||
                                element.content == "false" ||
                                strictJsonNumber.matches(element.content),
                        ) {
                            "Search JSON contains an invalid literal."
                        }
                    }
                }
                is JsonArray -> element.forEach { appendJson(it, depth + 1) }
                is JsonObject -> element.keys.sorted().forEach { appendJson(element.getValue(it), depth + 1) }
            }
        }

        private fun appendLeaf(
            text: String,
            leafSegmentChars: Int,
        ) {
            if (text.isEmpty()) return
            require(leafSegmentChars > 0) { "Search leaf segment size is invalid." }
            val segmentCount = (text.length + leafSegmentChars - 1) / leafSegmentChars
            require(leafCount <= MAX_LEAF_COUNT - segmentCount) { "Search leaf count exceeds the limit." }
            require(value.length + text.length + 1 <= MAX_SEARCH_TEXT_CHARS) {
                "Search text exceeds the size limit."
            }
            value.append(text).append('\n')
            leafCount += segmentCount
        }
    }
}
