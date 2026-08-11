package app.zhijuan.core.database.search

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus

internal sealed interface MemorySearchAuthoritativeSourceV1 {
    val sourceType: MemorySearchSourceTypeV1
    val sourceId: String
}

internal class StoryEntitySourceV1(
    val story: StoryEntity,
) : MemorySearchAuthoritativeSourceV1 {
    override val sourceType = MemorySearchSourceTypeV1.STORY_ENTITY
    override val sourceId: String = story.entityId

    override fun toString(): String =
        "StoryEntitySourceV1(sourceType=STORY_ENTITY, source=redacted)"
}

internal class ChapterSummarySourceV1(
    val summary: ChapterSummaryEntity,
) : MemorySearchAuthoritativeSourceV1 {
    override val sourceType = MemorySearchSourceTypeV1.CHAPTER_SUMMARY
    override val sourceId: String = summary.chapterSummaryId

    override fun toString(): String =
        "ChapterSummarySourceV1(sourceType=CHAPTER_SUMMARY, source=redacted)"
}

internal class EntityEventSourceV1(
    val event: EntityEventEntity,
    val chapterIndex: Int,
) : MemorySearchAuthoritativeSourceV1 {
    override val sourceType = MemorySearchSourceTypeV1.ENTITY_EVENT
    override val sourceId: String = event.entityEventId

    override fun toString(): String =
        "EntityEventSourceV1(sourceType=ENTITY_EVENT, source=redacted)"
}

internal class CanonFactSourceV1(
    val fact: CanonFactEntity,
    val chapterIndex: Int?,
    val bibleSourceIsCurrent: Boolean,
) : MemorySearchAuthoritativeSourceV1 {
    override val sourceType = MemorySearchSourceTypeV1.CANON_FACT
    override val sourceId: String = fact.canonFactId

    override fun toString(): String =
        "CanonFactSourceV1(sourceType=CANON_FACT, source=redacted)"
}

internal class TimelineEventSourceV1(
    val timeline: TimelineEventEntity,
    val chapterIndex: Int,
) : MemorySearchAuthoritativeSourceV1 {
    override val sourceType = MemorySearchSourceTypeV1.TIMELINE_EVENT
    override val sourceId: String = timeline.timelineEventId

    override fun toString(): String =
        "TimelineEventSourceV1(sourceType=TIMELINE_EVENT, source=redacted)"
}

internal class ForeshadowSourceV1(
    val foreshadow: ForeshadowItemEntity,
    val chapterIndex: Int?,
) : MemorySearchAuthoritativeSourceV1 {
    override val sourceType = MemorySearchSourceTypeV1.FORESHADOW
    override val sourceId: String = foreshadow.foreshadowItemId

    override fun toString(): String =
        "ForeshadowSourceV1(sourceType=FORESHADOW, source=redacted)"
}

internal data class MemorySearchHydratedHitV1(
    val recallHit: MemorySearchRecallHitV1,
    val authoritativeSource: MemorySearchAuthoritativeSourceV1,
) {
    override fun toString(): String =
        "MemorySearchHydratedHitV1(recallHit=$recallHit, authoritativeSource=$authoritativeSource)"
}

internal data class MemorySearchHydrationResultV1(
    val hits: List<MemorySearchHydratedHitV1>,
    val inputPointerCount: Int,
    val rejectedPointerCount: Int,
    val indexRebuildRequired: Boolean,
    val queryFingerprint: String,
) {
    init {
        require(inputPointerCount in 0..128) { "Hydration input pointer count is invalid." }
        require(hits.size in 0..inputPointerCount) { "Hydrated hit count is invalid." }
        require(rejectedPointerCount in 0..inputPointerCount) {
            "Rejected pointer count is invalid."
        }
        require(hits.size + rejectedPointerCount == inputPointerCount) {
            "Hydration accounting is inconsistent."
        }
        require(indexRebuildRequired == (rejectedPointerCount > 0)) {
            "Hydration index rebuild flag is inconsistent."
        }
        require(queryFingerprint.matches(SHA256_PATTERN)) {
            "Hydration query fingerprint is invalid."
        }
    }

    override fun toString(): String =
        "MemorySearchHydrationResultV1(hitCount=${hits.size}, " +
            "inputPointerCount=$inputPointerCount, rejectedPointerCount=$rejectedPointerCount, " +
            "indexRebuildRequired=$indexRebuildRequired, queryFingerprint=$queryFingerprint, " +
            "hits=redacted)"

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/**
 * Reloads the authoritative rows behind a recall result in at most six bounded queries. Derived
 * pointers are accepted only when the existing factory recreates every field except SQLite rowid.
 * Missing, expired or mismatched pointers are omitted with explicit rebuild evidence.
 */
internal class MemorySearchHydrationRepositoryV1(
    private val database: ZhijuanDatabase,
) {
    suspend fun hydrate(
        bookId: String,
        targetChapterIndex: Int,
        recallResult: MemorySearchRecallResultV1,
    ): MemorySearchHydrationResultV1 = database.withTransaction {
        require(bookId.isNotBlank() && bookId.length <= MAX_ID_CHARS) {
            "Hydration book identity is invalid."
        }
        require(targetChapterIndex >= 1) { "Hydration target chapter is invalid." }
        require(recallResult.hits.size <= MAX_POINTERS) {
            "Hydration input exceeds the document limit."
        }

        val identities = mutableSetOf<SourceIdentity>()
        val documentIds = mutableSetOf<String>()
        val idsByType = MemorySearchSourceTypeV1.entries.associateWith { linkedSetOf<String>() }
        recallResult.hits.forEach { hit ->
            val document = hit.document
            requireValidPointer(document, bookId, targetChapterIndex)
            val sourceType = MemorySearchSourceTypeV1.entries.firstOrNull {
                it.name == document.sourceType
            } ?: throw IllegalArgumentException("Hydration pointer source type is invalid.")
            require(documentIds.add(document.documentId)) {
                "Hydration pointers contain duplicate document identities."
            }
            require(identities.add(SourceIdentity(sourceType, document.sourceId))) {
                "Hydration pointers contain duplicate source identities."
            }
            idsByType.getValue(sourceType).add(document.sourceId)
        }

        val memory = database.memoryDao()
        val sources = mutableMapOf<SourceIdentity, MemorySearchAuthoritativeSourceV1>()
        fun putSource(source: MemorySearchAuthoritativeSourceV1) {
            check(sources.put(SourceIdentity(source.sourceType, source.sourceId), source) == null) {
                "Hydration query returned duplicate source identities."
            }
        }

        idsByType.getValue(MemorySearchSourceTypeV1.STORY_ENTITY).takeIf { it.isNotEmpty() }
            ?.let { ids ->
                memory.hydrateStoryEntities(bookId, ids.toList()).forEach { story ->
                    putSource(StoryEntitySourceV1(story))
                }
            }
        idsByType.getValue(MemorySearchSourceTypeV1.CHAPTER_SUMMARY).takeIf { it.isNotEmpty() }
            ?.let { ids ->
                memory.hydrateChapterSummaries(bookId, targetChapterIndex, ids.toList())
                    .forEach { summary -> putSource(ChapterSummarySourceV1(summary)) }
            }
        idsByType.getValue(MemorySearchSourceTypeV1.ENTITY_EVENT).takeIf { it.isNotEmpty() }
            ?.let { ids ->
                memory.hydrateEntityEvents(bookId, targetChapterIndex, ids.toList()).forEach { row ->
                    val chapterIndex = requireNotNull(row.chapterIndex) {
                        "Hydrated entity-event chapter is missing."
                    }
                    putSource(EntityEventSourceV1(row.entityEvent, chapterIndex))
                }
            }
        idsByType.getValue(MemorySearchSourceTypeV1.CANON_FACT).takeIf { it.isNotEmpty() }
            ?.let { ids ->
                memory.hydrateCanonFacts(bookId, targetChapterIndex, ids.toList()).forEach { row ->
                    putSource(
                        CanonFactSourceV1(
                            fact = row.canonFact,
                            chapterIndex = row.chapterIndex,
                            bibleSourceIsCurrent = row.bibleSourceIsCurrent,
                        ),
                    )
                }
            }
        idsByType.getValue(MemorySearchSourceTypeV1.TIMELINE_EVENT).takeIf { it.isNotEmpty() }
            ?.let { ids ->
                memory.hydrateTimelineEvents(bookId, targetChapterIndex, ids.toList()).forEach { row ->
                    val chapterIndex = requireNotNull(row.chapterIndex) {
                        "Hydrated timeline-event chapter is missing."
                    }
                    putSource(TimelineEventSourceV1(row.timelineEvent, chapterIndex))
                }
            }
        idsByType.getValue(MemorySearchSourceTypeV1.FORESHADOW).takeIf { it.isNotEmpty() }
            ?.let { ids ->
                memory.hydrateForeshadows(bookId, targetChapterIndex, ids.toList()).forEach { row ->
                    putSource(ForeshadowSourceV1(row.foreshadow, row.chapterIndex))
                }
            }

        val hydratedHits = ArrayList<MemorySearchHydratedHitV1>(recallResult.hits.size)
        var rejectedPointerCount = 0
        recallResult.hits.forEach { hit ->
            val pointer = hit.document
            val sourceType = MemorySearchSourceTypeV1.valueOf(pointer.sourceType)
            val source = sources[SourceIdentity(sourceType, pointer.sourceId)]
            val derived = source?.let { deriveAuthoritativeDocument(it, bookId, targetChapterIndex) }
            if (source != null && derived != null && derived == pointer.copy(rowId = 0L)) {
                hydratedHits.add(MemorySearchHydratedHitV1(hit, source))
            } else {
                rejectedPointerCount += 1
            }
        }

        MemorySearchHydrationResultV1(
            hits = hydratedHits,
            inputPointerCount = recallResult.hits.size,
            rejectedPointerCount = rejectedPointerCount,
            indexRebuildRequired = rejectedPointerCount > 0,
            queryFingerprint = recallResult.queryFingerprint,
        )
    }

    private fun requireValidPointer(
        document: MemorySearchDocumentEntity,
        bookId: String,
        targetChapterIndex: Int,
    ) {
        require(document.rowId > 0L) { "Hydration pointer row identity is invalid." }
        require(document.documentId.isNotBlank() && document.documentId.length <= MAX_ID_CHARS) {
            "Hydration pointer document identity is invalid."
        }
        require(document.bookId == bookId) { "Hydration pointer belongs to another book." }
        require(document.sourceId.isNotBlank() && document.sourceId.length <= MAX_ID_CHARS) {
            "Hydration pointer source identity is invalid."
        }
        require(document.chapterIndex == null || document.chapterIndex in 1 until targetChapterIndex) {
            "Hydration pointer is outside the chapter boundary."
        }
        require(document.storyOrder == null || document.storyOrder >= 0L) {
            "Hydration pointer story order is invalid."
        }
        require(document.importance in 0..100) { "Hydration pointer importance is invalid." }
        require(document.sourceContentHash.matches(SHA256_PATTERN)) {
            "Hydration pointer source hash is invalid."
        }
        require(document.searchTerms.isNotEmpty() && document.searchTerms.all { it.code < 128 }) {
            "Hydration pointer search terms are invalid."
        }
        require(document.updatedAt >= 0L) { "Hydration pointer update time is invalid." }
    }

    private fun deriveAuthoritativeDocument(
        source: MemorySearchAuthoritativeSourceV1,
        bookId: String,
        targetChapterIndex: Int,
    ): MemorySearchDocumentEntity? = when (source) {
        is StoryEntitySourceV1 -> source.story.takeIf {
            it.bookId == bookId && it.archivedAt == null
        }?.let(MemorySearchDocumentFactoryV1::from)

        is ChapterSummarySourceV1 -> source.summary.takeIf {
            it.bookId == bookId &&
                it.status == DerivedDataStatus.VALID &&
                it.chapterIndex in 1 until targetChapterIndex
        }?.let(MemorySearchDocumentFactoryV1::from)

        is EntityEventSourceV1 -> source.event.takeIf {
            it.bookId == bookId &&
                it.status == DerivedDataStatus.VALID &&
                source.chapterIndex in 1 until targetChapterIndex
        }?.let { MemorySearchDocumentFactoryV1.from(it, source.chapterIndex) }

        is CanonFactSourceV1 -> source.fact.takeIf {
            val chapterSourceIsCurrent = it.sourceChapterVersionId != null &&
                source.chapterIndex != null &&
                source.chapterIndex in 1 until targetChapterIndex
            it.bookId == bookId &&
                it.status == DerivedDataStatus.VALID &&
                (source.bibleSourceIsCurrent || chapterSourceIsCurrent)
        }?.let { MemorySearchDocumentFactoryV1.from(it, source.chapterIndex) }

        is TimelineEventSourceV1 -> source.timeline.takeIf {
            it.bookId == bookId &&
                it.status == DerivedDataStatus.VALID &&
                source.chapterIndex in 1 until targetChapterIndex
        }?.let { MemorySearchDocumentFactoryV1.from(it, source.chapterIndex) }

        is ForeshadowSourceV1 -> source.foreshadow.takeIf {
            val sourceChapterIsCurrent = it.sourceChapterVersionId == null ||
                (source.chapterIndex != null && source.chapterIndex in 1 until targetChapterIndex)
            it.bookId == bookId &&
                it.memoryStatus == DerivedDataStatus.VALID &&
                it.foreshadowStatus != ForeshadowStatus.RESOLVED &&
                it.foreshadowStatus != ForeshadowStatus.ABANDONED &&
                sourceChapterIsCurrent
        }?.let { MemorySearchDocumentFactoryV1.from(it, source.chapterIndex) }
    }

    private data class SourceIdentity(
        val sourceType: MemorySearchSourceTypeV1,
        val sourceId: String,
    )

    private companion object {
        const val MAX_POINTERS = 128
        const val MAX_ID_CHARS = 256
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
