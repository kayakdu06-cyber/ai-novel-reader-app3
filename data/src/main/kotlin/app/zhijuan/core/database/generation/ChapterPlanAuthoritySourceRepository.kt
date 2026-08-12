package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.model.RelevantCharacterAdultGate
import app.zhijuan.core.task.BoundPromptBundle
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ChapterPlanAuthoritySource(
    val bookId: String,
    val jobId: String,
    val planStageId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val creationSnapshotContentHash: String,
    val rawInputJson: String,
    val normalizedInputJson: String,
    val chapterTaskText: String,
    val capabilityHintIds: Set<String>,
    val obligationIds: Set<String>,
    val knownCharacterIds: Set<String>,
    val confirmedAdultFictionalCharacterIds: Set<String>,
    val intimacyRelevant: Boolean,
    val adultGate: RelevantCharacterAdultGate,
    val promptBundle: BoundPromptBundle,
    val context: ReadyChapterContext,
) {
    override fun toString(): String =
        "ChapterPlanAuthoritySource(chapterIndex=$chapterIndex, characterCount=${knownCharacterIds.size}, " +
            "content=redacted)"
}

/** Rebuilds the local authority needed to freeze chapter-plan v2 before context activation. */
class ChapterPlanAuthoritySourceRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun loadAfterContextAssembly(
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        context: ReadyChapterContext,
        loadedAt: Long,
    ): ChapterPlanAuthoritySource = database.withTransaction {
        require(snapshot.route == GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1)
        val lease = snapshot.executionLease
        val generation = database.generationDao()
        val contextStage = requireNotNull(generation.findStage(lease.stageId))
        val job = requireNotNull(generation.findJob(lease.jobId))
        require(
            job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSED) &&
                contextStage.status == GenerationStageStatus.SUCCEEDED &&
                contextStage.jobId == job.jobId && context.contextStageId == contextStage.stageId &&
                context.chapterPlanStageId == generation.stagesForJob(job.jobId).single {
                    it.phase == GenerationPhase.BUILD_CHAPTER_PLAN
                }.stageId && loadedAt >= contextStage.updatedAt,
        ) { "Chapter-plan authority context is not the just-committed bound result." }
        val book = requireNotNull(database.libraryDao().findBook(job.bookId))
        val creation = requireNotNull(database.libraryDao().findCreationSnapshot(book.creationSnapshotId))
        val head = requireNotNull(database.memoryDao().findMemoryHead(book.bookId))
        val bible = requireNotNull(head.currentBibleRevisionId?.let { revisionId ->
            database.memoryDao().findBibleRevision(revisionId)
        })
        val entities = database.memoryDao().activeEntitiesForBible(book.bookId, bible.bibleRevisionId, 512)
        require(entities.isNotEmpty()) { "Chapter-plan authority has no known characters." }
        val confirmedAdults = entities.filter { entity -> entity.adultStatus == AdultStatus.CONFIRMED_ADULT }
            .mapTo(linkedSetOf()) { it.entityId }
        val outline = requireNotNull(head.currentOutlineRevisionId?.let { revisionId ->
            database.memoryDao().findOutlineRevision(revisionId)
        })
        val chapterIndex = requireNotNull(database.libraryDao().findChapter(contextStage.targetId)).chapterIndex
        val chapterNode = findChapterNode(outline, chapterIndex, context.contextSnapshotId)
        val task = chapterNode.planJson
        val taskRoot = runCatching { Json.parseToJsonElement(task) as JsonObject }
            .getOrElse { error("Chapter-plan authority task is malformed.") }
        val capabilityHints = taskRoot.stringSet("capabilityHints")
        val obligationIds = taskRoot.stringSet("obligationIds")
        val intimacyRelevant = "intimacy-continuity" in capabilityHints || INTIMACY_SIGNALS.any(task::contains)
        ChapterPlanAuthoritySource(
            bookId = book.bookId,
            jobId = job.jobId,
            planStageId = context.chapterPlanStageId,
            chapterId = contextStage.targetId,
            chapterIndex = chapterIndex,
            creationSnapshotContentHash = creation.contentHash,
            rawInputJson = creation.rawInputJson,
            normalizedInputJson = creation.normalizedInputJson,
            chapterTaskText = task,
            capabilityHintIds = capabilityHints,
            obligationIds = obligationIds,
            knownCharacterIds = entities.mapTo(linkedSetOf()) { it.entityId },
            confirmedAdultFictionalCharacterIds = confirmedAdults,
            intimacyRelevant = intimacyRelevant,
            adultGate = when {
                !intimacyRelevant -> RelevantCharacterAdultGate.UNKNOWN
                confirmedAdults.isNotEmpty() -> RelevantCharacterAdultGate.CONFIRMED_ADULTS
                entities.any { it.adultStatus == AdultStatus.UNKNOWN } -> RelevantCharacterAdultGate.UNKNOWN
                else -> RelevantCharacterAdultGate.NOT_CONFIRMED
            },
            promptBundle = PromptBundleBindingRepository(database).bindForBook(book.bookId),
            context = context,
        )
    }

    private suspend fun findChapterNode(
        current: app.zhijuan.core.database.memory.OutlineRevisionEntity,
        chapterIndex: Int,
        contextId: String,
    ): app.zhijuan.core.database.memory.OutlineNodeEntity {
        var cursor: app.zhijuan.core.database.memory.OutlineRevisionEntity? = current
        var visited = 0
        while (cursor != null && visited++ < 2_000) {
            val match = database.memoryDao().findOutlineNodes(cursor.outlineRevisionId).singleOrNull {
                it.nodeType == OutlineNodeType.CHAPTER && it.plannedChapterIndex == chapterIndex
            }
            if (match != null) {
                require(sha256(match.planJson) == match.contentHash)
                return match
            }
            cursor = cursor.parentRevisionId?.let { revisionId ->
                database.memoryDao().findOutlineRevision(revisionId)
            }
        }
        error("Chapter-plan authority outline is missing: $contextId")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private val INTIMACY_SIGNALS = listOf("亲密", "性关系", "成人关系", "身体关系", "情欲")

    private fun JsonObject.stringSet(key: String): Set<String> =
        ((this[key] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        } ?: emptyList()).toCollection(linkedSetOf())
}
