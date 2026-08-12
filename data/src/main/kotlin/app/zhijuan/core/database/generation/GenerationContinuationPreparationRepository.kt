package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.task.ArcPlanningWindowInput
import app.zhijuan.core.task.ChapterContextBudgetSpec
import app.zhijuan.core.task.ChapterContextLimitSource
import app.zhijuan.core.task.FirstChapterGenerationMode
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

sealed interface GenerationContinuationPreparationResult {
    data class Prepared(
        val bookId: String,
        val jobId: String,
        val chapterIndex: Int?,
        val replayed: Boolean,
    ) : GenerationContinuationPreparationResult

    data object NotReady : GenerationContinuationPreparationResult
}

/**
 * Creates only the deterministic next durable Job after a completed predecessor. It never opens a
 * Provider. Initial planning unlocks the first rolling window; a committed window unlocks chapter 1.
 */
class GenerationContinuationPreparationRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun prepareAfterCompleted(
        completedJobId: String,
        preparedAt: Long,
    ): GenerationContinuationPreparationResult = database.withTransaction {
        require(IDENTIFIER.matches(completedJobId) && preparedAt >= 0L)
        val generation = database.generationDao()
        val completed = generation.findJob(completedJobId)
            ?: return@withTransaction GenerationContinuationPreparationResult.NotReady
        if (completed.status != GenerationJobStatus.COMPLETED) {
            return@withTransaction GenerationContinuationPreparationResult.NotReady
        }
        when {
            completed.jobType == GenerationJobType.CREATE_BOOK -> prepareFirstWindow(completed, preparedAt)
            completed.jobType == GenerationJobType.CONTINUE_BOOK &&
                generation.stagesForJob(completed.jobId).singleOrNull()?.phase == GenerationPhase.BUILD_ARC_PLAN ->
                prepareFirstChapter(completed, preparedAt)
            else -> GenerationContinuationPreparationResult.NotReady
        }
    }

    private suspend fun prepareFirstWindow(
        completed: GenerationJobEntity,
        preparedAt: Long,
    ): GenerationContinuationPreparationResult {
        val generation = database.generationDao()
        val memory = database.memoryDao()
        val masterStage = generation.stagesForJob(completed.jobId).singleOrNull {
            it.phase == GenerationPhase.BUILD_MASTER_OUTLINE
        } ?: return GenerationContinuationPreparationResult.NotReady
        if (masterStage.status != GenerationStageStatus.SUCCEEDED) {
            return GenerationContinuationPreparationResult.NotReady
        }
        val head = memory.findMemoryHead(completed.bookId)
            ?: return GenerationContinuationPreparationResult.NotReady
        val master = head.currentOutlineRevisionId?.let { memory.findOutlineRevision(it) }
            ?: return GenerationContinuationPreparationResult.NotReady
        if (
            master.generationStageId != masterStage.stageId || master.revisionNo != 1 ||
            master.parentRevisionId != null || master.bookId != completed.bookId
        ) return GenerationContinuationPreparationResult.NotReady
        val outline = parseObject(master.summaryJson)
        val targetChapterCount = outline.long("targetChapterCount").toInt()
        val beats = outline["beats"] as? kotlinx.serialization.json.JsonArray
            ?: return GenerationContinuationPreparationResult.NotReady
        val firstBeat = beats.firstOrNull() as? JsonObject
            ?: return GenerationContinuationPreparationResult.NotReady
        val suffix = sha256("zhijuan.first-window.v1\u0000${completed.jobId}\u0000${master.contentHash}").take(32)
        val setup = ArcWindowPlanningJobFactory.create(
            ArcWindowPlanningJobSpec(
                jobId = "job.window.$suffix",
                stageId = "stage.window.$suffix",
                bookId = completed.bookId,
                userIntentJson = completed.userIntentJson,
                budgetSnapshotJson = completed.budgetSnapshotJson,
                masterOutlineRevisionId = master.outlineRevisionId,
                masterOutlineContentHash = master.contentHash,
                parentOutlineRevisionId = master.outlineRevisionId,
                parentOutlineContentHash = master.contentHash,
                windowInput = ArcPlanningWindowInput(
                    targetChapterCount = targetChapterCount,
                    nextChapterIndex = 1,
                    enclosingBeatStartChapter = firstBeat.long("startChapter").toInt(),
                    enclosingBeatEndChapter = firstBeat.long("endChapter").toInt(),
                ),
                createdAt = preparedAt,
            ),
        ).generationSetup
        val replayed = generation.findJob(setup.jobId) != null
        createReadyOrReplay(setup, preparedAt)
        return GenerationContinuationPreparationResult.Prepared(
            completed.bookId,
            setup.jobId,
            chapterIndex = null,
            replayed = replayed,
        )
    }

    private suspend fun prepareFirstChapter(
        completed: GenerationJobEntity,
        preparedAt: Long,
    ): GenerationContinuationPreparationResult {
        val memory = database.memoryDao()
        val generation = database.generationDao()
        val head = memory.findMemoryHead(completed.bookId)
            ?: return GenerationContinuationPreparationResult.NotReady
        val window = head.currentOutlineRevisionId?.let { memory.findOutlineRevision(it) }
            ?: return GenerationContinuationPreparationResult.NotReady
        val windowStage = window.generationStageId?.let { generation.findStage(it) }
            ?: return GenerationContinuationPreparationResult.NotReady
        if (
            windowStage.jobId != completed.jobId || windowStage.phase != GenerationPhase.BUILD_ARC_PLAN ||
            windowStage.status != GenerationStageStatus.SUCCEEDED
        ) return GenerationContinuationPreparationResult.NotReady
        val chapterNode = memory.findOutlineNodes(window.outlineRevisionId).singleOrNull {
            it.nodeType == OutlineNodeType.CHAPTER && it.plannedChapterIndex == 1
        } ?: return GenerationContinuationPreparationResult.NotReady
        val suffix = sha256("zhijuan.first-chapter.v1\u0000${completed.jobId}\u0000${window.contentHash}").take(32)
        val chapterId = "chapter.$suffix"
        val library = database.libraryDao()
        val existingChapter = library.findChapter(chapterId)
        if (existingChapter == null) {
            library.insertChapter(
                ChapterEntity(
                    chapterId = chapterId,
                    bookId = completed.bookId,
                    chapterIndex = 1,
                    plannedTitle = chapterNode.title,
                    displayTitle = chapterNode.title,
                    status = ChapterStatus.PLANNED,
                    consistencyStatus = ConsistencyStatus.UNKNOWN,
                    createdAt = preparedAt,
                    updatedAt = preparedAt,
                ),
            )
        } else {
            require(
                existingChapter.bookId == completed.bookId && existingChapter.chapterIndex == 1 &&
                    existingChapter.plannedTitle == chapterNode.title,
            ) { "First chapter replay changed its frozen identity." }
        }
        val progression = ChapterProgressionGateRepository(database).authorize(
            completed.bookId,
            chapterId,
            FirstChapterGenerationMode.FULL_PLANNING,
        ) as? ChapterProgressionAuthorization.Ready
            ?: return GenerationContinuationPreparationResult.NotReady
        val prompt = PromptBundleBindingRepository(database).bindForBook(completed.bookId)
        val requestLimit = parseObject(completed.budgetSnapshotJson).long("requestTokenHardLimit")
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(requestLimit >= MINIMUM_REQUEST_TOKENS) {
            "Confirmed request token limit is too small for chapter generation."
        }
        val setup = ChapterContextAssemblyJobFactory.create(
            ChapterContextAssemblyJobSpec(
                jobId = "job.chapter.$suffix",
                bookId = completed.bookId,
                chapterId = chapterId,
                chapterIndex = 1,
                userIntentJson = completed.userIntentJson,
                budgetSnapshotJson = completed.budgetSnapshotJson,
                promptBindingHash = prompt.bindingHash,
                contextBudget = ChapterContextBudgetSpec(
                    contextLimitTokens = null,
                    maximumOutputTokens = minOf(requestLimit, MAXIMUM_OUTPUT_TOKENS),
                    requestedOutputTokens = minOf(requestLimit, DEFAULT_REQUESTED_OUTPUT_TOKENS),
                    limitSource = ChapterContextLimitSource.UNKNOWN,
                    unknownLimitConfirmed = true,
                    tokenizerFamily = "conservative-utf8-v1",
                ),
                progressionPermit = progression.permit,
                stageIds = ChapterContextAssemblyStageIds(
                    contextStageId = "stage.context.$suffix",
                    chapterPlanStageId = "stage.plan.$suffix",
                ),
                createdAt = preparedAt,
            ),
        )
        val replayed = generation.findJob(setup.jobId) != null
        createReadyOrReplay(setup, preparedAt)
        return GenerationContinuationPreparationResult.Prepared(
            completed.bookId,
            setup.jobId,
            chapterIndex = 1,
            replayed = replayed,
        )
    }

    private suspend fun createReadyOrReplay(setup: GenerationJobSetup, preparedAt: Long) {
        val generation = database.generationDao()
        val existing = generation.findJob(setup.jobId)
        if (existing != null) {
            require(
                existing.bookId == setup.bookId && existing.jobType == setup.jobType &&
                    existing.userIntentJson == setup.userIntentJson &&
                    existing.budgetSnapshotJson == setup.budgetSnapshotJson &&
                    generation.stagesForJob(existing.jobId).map { stage ->
                        listOf(
                            stage.stageId,
                            stage.phase.name,
                            stage.targetType.name,
                            stage.targetId,
                            stage.inputVersionHash,
                            stage.idempotencyKey,
                            stage.inputSourcesJson,
                        )
                    } == setup.stages.map { stage ->
                        listOf(
                            stage.stageId,
                            stage.phase.name,
                            stage.targetType.name,
                            stage.targetId,
                            stage.inputVersionHash,
                            stage.idempotencyKey,
                            stage.inputSourcesJson,
                        )
                    },
            ) { "Continuation replay differs from its frozen setup." }
            return
        }
        GenerationJobSetupRepository(database).create(setup)
        val first = setup.stages.first()
        generation.transitionStage(
            stageId = first.stageId,
            expectedStatus = GenerationStageStatus.PENDING,
            event = StageEvent.DEPENDENCIES_SATISFIED,
            errorCode = null,
            nextRetryAt = null,
            updatedAt = preparedAt,
        )
        generation.transitionJob(
            setup.jobId,
            GenerationJobStatus.CREATED,
            JobEvent.VALIDATION_PASSED,
            preparedAt,
        )
    }

    private fun parseObject(value: String): JsonObject =
        runCatching { Json.parseToJsonElement(value) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Continuation source JSON is invalid.") }

    private fun JsonObject.long(key: String): Long =
        (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.long
            ?: throw IllegalArgumentException("Continuation integer source is invalid: $key")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MINIMUM_REQUEST_TOKENS = 1_024
        const val MAXIMUM_OUTPUT_TOKENS = 16_384
        const val DEFAULT_REQUESTED_OUTPUT_TOKENS = 8_192
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}
