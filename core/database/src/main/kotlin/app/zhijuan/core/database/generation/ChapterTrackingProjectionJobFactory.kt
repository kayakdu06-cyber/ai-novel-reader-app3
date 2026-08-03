package app.zhijuan.core.database.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class ChapterTrackingProjectionSourceV1(
    val chapterVersionId: String,
    val chapterContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val memorySnapshotHash: String,
    val priorForeshadowSnapshotHash: String,
    val knownEntitySnapshotHash: String,
) {
    init {
        require(IDENTIFIER.matches(chapterVersionId) && IDENTIFIER.matches(chapterId))
        require(listOf(chapterContentHash, memorySnapshotHash, priorForeshadowSnapshotHash, knownEntitySnapshotHash).all(HASH::matches))
        require(chapterIndex in 1..10_000)
    }
}

data class ChapterTrackingProjectionInputs(
    val source: ChapterTrackingProjectionSourceV1,
    val chapterContent: String,
    val summary: ChapterSummaryEntity,
    val entityEvents: List<EntityEventEntity>,
    val canonFacts: List<CanonFactEntity>,
    val knownEntities: List<StoryEntity>,
    val priorForeshadows: List<ForeshadowItemEntity>,
) {
    override fun toString(): String =
        "ChapterTrackingProjectionInputs(chapterIndex=${source.chapterIndex}, " +
            "eventCount=${entityEvents.size}, factCount=${canonFacts.size}, " +
            "entityCount=${knownEntities.size}, foreshadowCount=${priorForeshadows.size}, content=redacted)"
}

data class ChapterTrackingProjectionJobSpec(
    val jobId: String,
    val stageId: String,
    val bookId: String,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val source: ChapterTrackingProjectionSourceV1,
    val maxAttempts: Int = 2,
    val createdAt: Long,
)

object ChapterTrackingProjectionJobFactory {
    const val SOURCE_POLICY_VERSION = "zhijuan.chapter-tracking-source.v1"
    const val OUTPUT_SCHEMA_ID = "chapter-story-tracking.v1"

    fun create(spec: ChapterTrackingProjectionJobSpec): GenerationJobSetup {
        require(listOf(spec.jobId, spec.stageId, spec.bookId).all(IDENTIFIER::matches))
        require(spec.maxAttempts in 1..2) { "Story tracking permits at most one format repair." }
        require(spec.createdAt >= 0L)
        val inputSources = inputSources(spec.source)
        val inputVersionHash = sourceInputHash(inputSources)
        return GenerationJobSetup(
            jobId = spec.jobId,
            bookId = spec.bookId,
            jobType = GenerationJobType.REBUILD_MEMORY,
            userIntentJson = spec.userIntentJson,
            budgetSnapshotJson = spec.budgetSnapshotJson,
            promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
            stages = listOf(
                GenerationStageSetup(
                    stageId = spec.stageId,
                    phase = GenerationPhase.EXTRACT_MEMORY,
                    targetType = GenerationTargetType.CHAPTER,
                    targetId = spec.source.chapterId,
                    inputVersionHash = inputVersionHash,
                    idempotencyKey = StageIdempotencyKey.create(
                        jobId = spec.jobId,
                        phase = GenerationPhase.EXTRACT_MEMORY,
                        targetId = spec.source.chapterId,
                        inputVersionHash = inputVersionHash,
                    ).value,
                    maxAttempts = spec.maxAttempts,
                    inputSourcesJson = inputSources,
                ),
            ),
            createdAt = spec.createdAt,
        )
    }

    internal fun parseAndVerify(stage: GenerationStageEntity): ChapterTrackingProjectionSourceV1 {
        require(stage.phase == GenerationPhase.EXTRACT_MEMORY)
        require(stage.targetType == GenerationTargetType.CHAPTER)
        val root = parseRoot(stage)
        require(root.keys == ROOT_KEYS)
        require(root.int("schemaVersion") == 1)
        require(root.string("sourcePolicyVersion") == SOURCE_POLICY_VERSION)
        require(root.string("promptBundleVersion") == PromptBundleCatalogV1.BUNDLE_VERSION)
        require(root.string("outputSchemaId") == OUTPUT_SCHEMA_ID)
        val sourceObject = root["trackingSource"] as? JsonObject
            ?: throw IllegalArgumentException("Story-tracking source binding is missing.")
        require(sourceObject.keys == SOURCE_KEYS)
        val source = ChapterTrackingProjectionSourceV1(
            chapterVersionId = sourceObject.string("chapterVersionId"),
            chapterContentHash = sourceObject.string("chapterContentHash"),
            chapterId = sourceObject.string("chapterId"),
            chapterIndex = sourceObject.int("chapterIndex"),
            memorySnapshotHash = sourceObject.string("memorySnapshotHash"),
            priorForeshadowSnapshotHash = sourceObject.string("priorForeshadowSnapshotHash"),
            knownEntitySnapshotHash = sourceObject.string("knownEntitySnapshotHash"),
        )
        require(source.chapterId == stage.targetId)
        require(stage.inputVersionHash == sourceInputHash(stage.inputSourcesJson)) {
            "Story-tracking input hash does not match its frozen source binding."
        }
        return source
    }

    internal fun inputSources(source: ChapterTrackingProjectionSourceV1): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourcePolicyVersion" to JsonPrimitive(SOURCE_POLICY_VERSION),
            "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
            "outputSchemaId" to JsonPrimitive(OUTPUT_SCHEMA_ID),
            "trackingSource" to JsonObject(
                linkedMapOf(
                    "chapterVersionId" to JsonPrimitive(source.chapterVersionId),
                    "chapterContentHash" to JsonPrimitive(source.chapterContentHash),
                    "chapterId" to JsonPrimitive(source.chapterId),
                    "chapterIndex" to JsonPrimitive(source.chapterIndex),
                    "memorySnapshotHash" to JsonPrimitive(source.memorySnapshotHash),
                    "priorForeshadowSnapshotHash" to JsonPrimitive(source.priorForeshadowSnapshotHash),
                    "knownEntitySnapshotHash" to JsonPrimitive(source.knownEntitySnapshotHash),
                ),
            ),
        ),
    ).toString()

    internal fun isBound(stage: GenerationStageEntity): Boolean =
        stage.phase == GenerationPhase.EXTRACT_MEMORY &&
            runCatching { parseRoot(stage).string("outputSchemaId") == OUTPUT_SCHEMA_ID }.getOrDefault(false)

    private fun sourceInputHash(inputSources: String): String = stableHash(
        "zhijuan.chapter-tracking-stage-input.v1",
        SOURCE_POLICY_VERSION,
        OUTPUT_SCHEMA_ID,
        inputSources,
    )

    private fun parseRoot(stage: GenerationStageEntity): JsonObject =
        runCatching { STRICT_JSON.parseToJsonElement(stage.inputSourcesJson) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Story-tracking source binding is invalid JSON.") }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw IllegalArgumentException("Story-tracking source field is missing: $key")

    private fun JsonObject.int(key: String): Int =
        (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw IllegalArgumentException("Story-tracking source field is missing: $key")

    private val STRICT_JSON = Json { isLenient = false }
    private val ROOT_KEYS = setOf(
        "schemaVersion", "sourcePolicyVersion", "promptBundleVersion", "outputSchemaId", "trackingSource",
    )
    private val SOURCE_KEYS = setOf(
        "chapterVersionId", "chapterContentHash", "chapterId", "chapterIndex",
        "memorySnapshotHash", "priorForeshadowSnapshotHash", "knownEntitySnapshotHash",
    )
}

class ChapterTrackingProjectionSourceRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun loadCurrentVersion(chapterId: String): ChapterTrackingProjectionInputs {
        require(IDENTIFIER.matches(chapterId))
        val library = database.libraryDao()
        val memory = database.memoryDao()
        val chapter = requireNotNull(library.findChapter(chapterId)) { "Story-tracking chapter does not exist." }
        val versionId = requireNotNull(chapter.currentVersionId) { "Story-tracking chapter has no current version." }
        val version = requireNotNull(library.findChapterVersion(versionId)) { "Story-tracking chapter version does not exist." }
        require(version.chapterId == chapter.chapterId)
        require(memory.findTrackingProjectionForVersion(versionId) == null) {
            "The current chapter version already has a story-tracking projection."
        }
        require(library.chaptersForBook(chapter.bookId).none { it.chapterIndex > chapter.chapterIndex && it.currentVersionId != null }) {
            "Story tracking must rebuild in chapter order before later committed chapters."
        }
        val summary = requireNotNull(memory.findSummaryForVersion(versionId)) {
            "Story tracking requires chapter memory for the same version."
        }
        val events = memory.entityEventsForVersion(versionId)
        val facts = memory.canonFactsForVersion(versionId)
        require(summary.status == DerivedDataStatus.VALID)
        require(events.all { it.status == DerivedDataStatus.VALID && it.sourceChapterVersionId == versionId })
        require(facts.all { it.status == DerivedDataStatus.VALID && it.sourceChapterVersionId == versionId })
        val head = requireNotNull(memory.findMemoryHead(chapter.bookId)) { "Story tracking requires a current story Bible." }
        val bibleId = requireNotNull(head.currentBibleRevisionId) { "Story tracking requires a current story Bible." }
        val entities = memory.activeEntitiesForBible(chapter.bookId, bibleId, MAX_ENTITIES + 1)
        require(entities.isNotEmpty() && entities.size <= MAX_ENTITIES) { "Story-tracking entity snapshot is empty or too large." }
        val foreshadows = memory.activeForeshadowsForProjection(chapter.bookId, MAX_FORESHADOWS + 1)
        require(foreshadows.size <= MAX_FORESHADOWS) { "Story-tracking foreshadow snapshot is too large." }
        val source = ChapterTrackingProjectionSourceV1(
            chapterVersionId = version.chapterVersionId,
            chapterContentHash = version.contentHash,
            chapterId = chapter.chapterId,
            chapterIndex = chapter.chapterIndex,
            memorySnapshotHash = memorySnapshotHash(summary, events, facts),
            priorForeshadowSnapshotHash = foreshadowSnapshotHash(foreshadows),
            knownEntitySnapshotHash = entitySnapshotHash(entities),
        )
        return ChapterTrackingProjectionInputs(
            source = source,
            chapterContent = version.content,
            summary = summary,
            entityEvents = events,
            canonFacts = facts,
            knownEntities = entities,
            priorForeshadows = foreshadows,
        )
    }

    internal suspend fun requireCurrentMatches(source: ChapterTrackingProjectionSourceV1, bookId: String) {
        val current = loadCurrentVersion(source.chapterId)
        require(current.source == source && current.summary.bookId == bookId) {
            "Story-tracking source snapshots are no longer current."
        }
    }

    companion object {
        const val MAX_ENTITIES = 256
        const val MAX_FORESHADOWS = 256

        fun memorySnapshotHash(
            summary: ChapterSummaryEntity,
            events: List<EntityEventEntity>,
            facts: List<CanonFactEntity>,
        ): String = stableHash(
            "zhijuan.chapter-memory-snapshot.v1",
            listOf(
                summary.chapterSummaryId, summary.bookId, summary.chapterVersionId, summary.chapterIndex,
                summary.schemaVersion, summary.summaryJson, summary.importance, summary.status.name,
                summary.modelSnapshotJson, summary.createdAt, summary.updatedAt,
            ),
            events.sortedWith(compareBy<EntityEventEntity>({ it.storyOrder }, { it.entityEventId })).map { event ->
                listOf(
                    event.entityEventId, event.bookId, event.entityId, event.sourceChapterVersionId,
                    event.storyOrder, event.attributeKey, event.oldValueJson, event.newValueJson,
                    event.storyTimeExpression, event.confidenceMicros, event.canonLevel.name,
                    event.evidenceJson, event.status.name, event.createdAt,
                )
            },
            facts.sortedBy { it.canonFactId }.map { fact ->
                listOf(
                    fact.canonFactId, fact.bookId, fact.entityId, fact.factText, fact.factPayloadJson,
                    fact.canonLevel.name, fact.scopeJson, fact.sourceChapterVersionId,
                    fact.sourceBibleRevisionId, fact.validFromStoryOrder, fact.validToStoryOrder,
                    fact.conflictGroupId, fact.status.name, fact.createdAt,
                )
            },
        )

        fun foreshadowSnapshotHash(items: List<ForeshadowItemEntity>): String = stableHash(
            "zhijuan.foreshadow-current-snapshot.v1",
            items.sortedBy { it.foreshadowItemId }.map { item ->
                listOf(
                    item.foreshadowItemId, item.bookId, item.description, item.foreshadowStatus.name,
                    item.memoryStatus.name, item.targetStartChapterIndex, item.targetEndChapterIndex,
                    item.sourceChapterVersionId, item.plantedChapterVersionId, item.resolvedChapterVersionId,
                    item.visibleEntityIdsJson, item.importance, item.source.name, item.createdAt, item.updatedAt,
                )
            },
        )

        fun entitySnapshotHash(items: List<StoryEntity>): String = stableHash(
            "zhijuan.story-entity-snapshot.v1",
            items.sortedBy { it.entityId }.map { entity ->
                listOf(
                    entity.entityId, entity.bookId, entity.entityType.name, entity.canonicalName,
                    entity.aliasesJson, entity.stableDefinitionJson, entity.adultStatus.name,
                    entity.ageYears, entity.sourceBibleRevisionId, entity.createdAt, entity.updatedAt, entity.archivedAt,
                )
            },
        )
    }
}

internal class ChapterTrackingProjectionSourceGuard(
    private val database: ZhijuanDatabase,
) {
    suspend fun requireProviderOpenAllowedIfBound(stage: GenerationStageEntity, job: GenerationJobEntity) {
        if (ChapterCandidateStageBindingV1.isBound(stage, ChapterCandidateArtifactRoleV1.TRACKING)) return
        if (!ChapterTrackingProjectionJobFactory.isBound(stage)) return
        val source = ChapterTrackingProjectionJobFactory.parseAndVerify(stage)
        ChapterTrackingProjectionSourceRepository(database).requireCurrentMatches(source, job.bookId)
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
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
