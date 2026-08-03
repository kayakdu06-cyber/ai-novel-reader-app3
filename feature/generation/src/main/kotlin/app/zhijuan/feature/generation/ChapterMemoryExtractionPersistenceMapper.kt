package app.zhijuan.feature.generation

import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.model.DerivedDataStatus
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ChapterMemoryExtractionMappingSpec(
    val bookId: String,
    val generationStageId: String,
    val modelSnapshotJson: String,
    val createdAt: Long,
) {
    init {
        require(listOf(bookId, generationStageId).all(MAPPING_IDENTIFIER::matches))
        require(modelSnapshotJson.isNotBlank() && modelSnapshotJson.length <= 65_536)
        require(createdAt >= 0L)
    }
}

data class ChapterMemoryDerivedDraft(
    val summary: ChapterSummaryEntity,
    val entityEvents: List<EntityEventEntity>,
    val canonFacts: List<CanonFactEntity>,
    val extractionContentHash: String,
) {
    override fun toString(): String =
        "ChapterMemoryDerivedDraft(eventCount=${entityEvents.size}, factCount=${canonFacts.size}, content=redacted)"
}

object ChapterMemoryExtractionPersistenceMapper {
    fun map(
        memory: ChapterMemoryV1,
        spec: ChapterMemoryExtractionMappingSpec,
    ): ChapterMemoryDerivedDraft {
        val baseOrder = Math.multiplyExact(memory.chapterIndex.toLong(), STORY_ORDER_STRIDE)
        val summaryJson = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "sourceChapterContentHash" to JsonPrimitive(memory.sourceChapterContentHash),
                "objectiveOutcome" to JsonPrimitive(memory.summary.objectiveOutcome),
                "keyEvents" to memory.summary.keyEvents.toJsonArray(),
                "decisions" to memory.summary.decisions.toJsonArray(),
                "relationshipChanges" to memory.summary.relationshipChanges.toJsonArray(),
                "endingState" to JsonPrimitive(memory.summary.endingState),
                "unresolvedQuestions" to memory.summary.unresolvedQuestions.toJsonArray(),
            ),
        ).toString()
        val summary = ChapterSummaryEntity(
            chapterSummaryId = stableId("summary", spec.generationStageId, memory.sourceChapterVersionId),
            bookId = spec.bookId,
            chapterVersionId = memory.sourceChapterVersionId,
            chapterIndex = memory.chapterIndex,
            schemaVersion = 1,
            summaryJson = summaryJson,
            importance = memory.summary.importance,
            status = DerivedDataStatus.VALID,
            modelSnapshotJson = spec.modelSnapshotJson,
            createdAt = spec.createdAt,
            updatedAt = spec.createdAt,
        )
        val events = memory.entityEvents.mapIndexed { index, event ->
            val storyOrder = Math.addExact(baseOrder, index.toLong() + 1L)
            EntityEventEntity(
                entityEventId = stableId("event", spec.generationStageId, memory.sourceChapterVersionId, index.toString()),
                bookId = spec.bookId,
                entityId = event.entityId,
                sourceChapterVersionId = memory.sourceChapterVersionId,
                storyOrder = storyOrder,
                attributeKey = event.attribute.name.lowercase(),
                oldValueJson = event.oldValue?.let(::valueJson),
                newValueJson = JsonObject(
                    linkedMapOf(
                        "value" to JsonPrimitive(event.newValue),
                        "relatedEntityId" to (event.relatedEntityId?.let(::JsonPrimitive) ?: JsonNull),
                    ),
                ).toString(),
                storyTimeExpression = event.storyTimeExpression,
                confidenceMicros = event.confidenceMicros,
                canonLevel = event.canonLevel,
                evidenceJson = JsonObject(
                    linkedMapOf(
                        "source" to JsonPrimitive("chapter-memory.v1"),
                        "sourceChapterContentHash" to JsonPrimitive(memory.sourceChapterContentHash),
                        "evidence" to JsonPrimitive(event.evidence),
                    ),
                ).toString(),
                status = DerivedDataStatus.VALID,
                createdAt = spec.createdAt,
            )
        }
        val facts = memory.facts.mapIndexed { index, fact ->
            CanonFactEntity(
                canonFactId = stableId("fact", spec.generationStageId, memory.sourceChapterVersionId, index.toString()),
                bookId = spec.bookId,
                entityId = fact.entityId,
                factText = fact.text,
                factPayloadJson = JsonObject(
                    linkedMapOf(
                        "schemaVersion" to JsonPrimitive(1),
                        "kind" to JsonPrimitive(fact.factKind.name),
                        "confidenceMicros" to JsonPrimitive(fact.confidenceMicros),
                        "sourceChapterContentHash" to JsonPrimitive(memory.sourceChapterContentHash),
                    ),
                ).toString(),
                canonLevel = fact.canonLevel,
                scopeJson = JsonObject(
                    linkedMapOf(
                        "fromChapter" to JsonPrimitive(memory.chapterIndex),
                        "throughChapter" to JsonNull,
                    ),
                ).toString(),
                sourceChapterVersionId = memory.sourceChapterVersionId,
                sourceBibleRevisionId = null,
                validFromStoryOrder = Math.addExact(baseOrder, memory.entityEvents.size.toLong() + index + 1L),
                validToStoryOrder = null,
                conflictGroupId = fact.conflictGroupId,
                status = DerivedDataStatus.VALID,
                createdAt = spec.createdAt,
            )
        }
        return ChapterMemoryDerivedDraft(
            summary = summary,
            entityEvents = events,
            canonFacts = facts,
            extractionContentHash = memory.contentHash,
        )
    }

    private fun List<String>.toJsonArray() = JsonArray(map(::JsonPrimitive))

    private fun valueJson(value: String) = JsonObject(mapOf("value" to JsonPrimitive(value))).toString()

    private fun stableId(prefix: String, vararg parts: String): String =
        "memory.$prefix.${sha256(parts.joinToString("\u0000")).take(32)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private const val STORY_ORDER_STRIDE = 1_000_000L
}

private val MAPPING_IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
