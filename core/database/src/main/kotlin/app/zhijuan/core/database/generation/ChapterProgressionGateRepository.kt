package app.zhijuan.core.database.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.OutlineRevisionEntity
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.task.ChapterPlanningEvidence
import app.zhijuan.core.task.ChapterProgressionBlockReason
import app.zhijuan.core.task.ChapterProgressionDecision
import app.zhijuan.core.task.FirstChapterGenerationMode
import app.zhijuan.core.task.FirstChapterProgressionPolicyV1
import app.zhijuan.core.task.PromptBundleCatalogV1
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class ChapterProgressionPermit internal constructor(
    private val evidence: JsonObject,
) {
    val evidenceHash: String = requireNotNull(
        (evidence["evidenceHash"] as? JsonPrimitive)?.contentOrNull,
    )

    fun bindInto(baseInputSourcesJson: String): String {
        val base = parseObject(baseInputSourcesJson, "Chapter input sources")
        require("chapterProgressionGate" !in base) { "Chapter progression evidence is already bound." }
        return JsonObject(base + ("chapterProgressionGate" to evidence)).toString()
    }

    override fun toString(): String = "ChapterProgressionPermit(evidence=redacted)"
}

sealed interface ChapterProgressionAuthorization {
    data class Ready(val permit: ChapterProgressionPermit) : ChapterProgressionAuthorization
    data class Blocked(val reason: ChapterProgressionBlockReason) : ChapterProgressionAuthorization
}

class ChapterProgressionGateRepository(
    private val database: ZhijuanDatabase,
) {
    suspend fun authorize(
        bookId: String,
        chapterId: String,
        mode: FirstChapterGenerationMode,
        seedStageId: String? = null,
        bootstrapStageId: String? = null,
    ): ChapterProgressionAuthorization {
        require(IDENTIFIER.matches(bookId) && IDENTIFIER.matches(chapterId))
        val chapter = database.libraryDao().findChapter(chapterId)
            ?: return blocked(ChapterProgressionBlockReason.TARGET_CHAPTER_WINDOW_MISSING)
        require(chapter.bookId == bookId) { "Chapter progression target belongs to another book." }
        val evidence = if (chapter.chapterIndex == 1 && mode == FirstChapterGenerationMode.FAST_LANE) {
            inspectFastLane(bookId, chapterId, requireNotNull(seedStageId), requireNotNull(bootstrapStageId))
        } else {
            inspectFullPlanning(bookId, chapterId, chapter.chapterIndex, mode)
        }
        val decision = FirstChapterProgressionPolicyV1.evaluate(chapter.chapterIndex, mode, evidence.policyEvidence)
        return when (decision) {
            ChapterProgressionDecision.Ready -> ChapterProgressionAuthorization.Ready(
                ChapterProgressionPermit(evidence.jsonWithHash()),
            )
            is ChapterProgressionDecision.Blocked -> blocked(decision.reason)
        }
    }

    internal suspend fun requireProviderOpenAllowed(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
    ) {
        if (job.promptBundleVersion != PromptBundleCatalogV1.BUNDLE_VERSION) return
        val input = parseObject(stage.inputSourcesJson, "Generation stage input sources")
        val postFirstChapter = input["postFirstChapterPlanning"] as? JsonObject
        if (postFirstChapter != null) {
            requirePostFirstChapterPlanningAllowed(stage, job, postFirstChapter)
            return
        }
        if (
            stage.targetType != GenerationTargetType.CHAPTER ||
            stage.phase !in REMOTE_CHAPTER_PHASES
        ) {
            return
        }
        val bootstrap = input["firstChapterBootstrap"] as? JsonObject
        if (bootstrap != null) {
            require(stage.phase == GenerationPhase.BUILD_CHAPTER_PLAN) {
                "Only the first-chapter bootstrap plan can use bootstrap evidence."
            }
            requireBootstrapProviderOpen(stage, job, bootstrap)
            return
        }
        val frozen = input["chapterProgressionGate"] as? JsonObject
            ?: throw StaleGenerationStateException("A supported chapter stage is missing progression evidence.")
        val mode = frozen.string("mode").let(FirstChapterGenerationMode::valueOf)
        val authorization = authorize(
            bookId = job.bookId,
            chapterId = stage.targetId,
            mode = mode,
            seedStageId = frozen.optionalString("seedStageId"),
            bootstrapStageId = frozen.optionalString("bootstrapStageId"),
        )
        val ready = authorization as? ChapterProgressionAuthorization.Ready
            ?: throw StaleGenerationStateException(
                "Chapter progression is blocked: ${(authorization as ChapterProgressionAuthorization.Blocked).reason}",
            )
        if (ready.permit.evidenceHash != frozen.string("evidenceHash")) {
            throw StaleGenerationStateException("Frozen chapter progression evidence is stale.")
        }
    }

    internal suspend fun requireContextAssemblyAllowed(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
    ) {
        require(job.promptBundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION)
        require(
            stage.phase == GenerationPhase.ASSEMBLE_CONTEXT &&
                stage.targetType == GenerationTargetType.CHAPTER &&
                stage.targetId.isNotBlank(),
        ) { "Chapter-context assembly stage is invalid." }
        val input = parseObject(stage.inputSourcesJson, "Chapter-context input sources")
        val frozen = input["chapterProgressionGate"] as? JsonObject
            ?: throw StaleGenerationStateException("Chapter-context assembly is missing progression evidence.")
        val mode = frozen.string("mode").let(FirstChapterGenerationMode::valueOf)
        val authorization = authorize(
            bookId = job.bookId,
            chapterId = stage.targetId,
            mode = mode,
            seedStageId = frozen.optionalString("seedStageId"),
            bootstrapStageId = frozen.optionalString("bootstrapStageId"),
        )
        val ready = authorization as? ChapterProgressionAuthorization.Ready
            ?: throw StaleGenerationStateException(
                "Chapter-context progression is blocked: " +
                    (authorization as ChapterProgressionAuthorization.Blocked).reason,
            )
        if (ready.permit.evidenceHash != frozen.string("evidenceHash")) {
            throw StaleGenerationStateException("Chapter-context progression evidence is stale.")
        }
    }

    private suspend fun requirePostFirstChapterPlanningAllowed(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        marker: JsonObject,
    ) {
        require(stage.phase in setOf(GenerationPhase.BUILD_BIBLE, GenerationPhase.BUILD_MASTER_OUTLINE))
        require(stage.targetId == job.bookId)
        require(marker.string("policyVersion") == FirstChapterProgressionPolicyV1.POLICY_VERSION)
        require(marker.string("bookId") == job.bookId && marker.int("chapterIndex") == 1)
        require(marker.string("bibleStageId").isNotBlank() && marker.string("outlineStageId").isNotBlank())
        require(
            (stage.phase == GenerationPhase.BUILD_BIBLE && stage.stageId == marker.string("bibleStageId")) ||
                (stage.phase == GenerationPhase.BUILD_MASTER_OUTLINE &&
                    stage.stageId == marker.string("outlineStageId")),
        )
        val library = database.libraryDao()
        val chapter = requireNotNull(library.findChapter(marker.string("chapterId")))
        val version = requireNotNull(library.findChapterVersion(marker.string("chapterVersionId")))
        require(
            chapter.bookId == job.bookId && chapter.chapterIndex == 1 &&
                chapter.currentVersionId == version.chapterVersionId && version.chapterId == chapter.chapterId &&
                version.contentHash == marker.string("chapterContentHash"),
        ) { "Post-first-chapter planning lost its frozen readable chapter version." }
        val seedStage = requireNotNull(database.generationDao().findStage(marker.string("seedStageId")))
        val seedJob = requireNotNull(database.generationDao().findJob(seedStage.jobId))
        require(
            seedStage.phase == GenerationPhase.BUILD_STORY_SEED &&
                seedStage.targetType == GenerationTargetType.BOOK && seedStage.targetId == job.bookId &&
                seedStage.status == GenerationStageStatus.SUCCEEDED && seedJob.bookId == job.bookId,
        ) { "Post-first-chapter planning lost its frozen story seed." }
        val seedOutput = parseOutput(seedStage)
        require(seedOutput.string("outputSchemaId") == "story-seed.v1")
        require(seedOutput.string("rawOutputHash") == marker.string("seedRawOutputHash"))
        require(seedOutput.string("contentHash") == marker.string("seedContentHash"))
        if (stage.phase == GenerationPhase.BUILD_MASTER_OUTLINE) {
            val bibleStage = requireNotNull(database.generationDao().findStage(marker.string("bibleStageId")))
            require(
                bibleStage.jobId == job.jobId && bibleStage.phase == GenerationPhase.BUILD_BIBLE &&
                    bibleStage.status == GenerationStageStatus.SUCCEEDED &&
                    parseOutput(bibleStage).string("outputSchemaId") == "story-bible.v1",
            ) { "Post-first-chapter master outline cannot start before its bound Bible succeeds." }
            val bibleInput = parseObject(bibleStage.inputSourcesJson, "Post-first-chapter Bible input")
            require(bibleInput["postFirstChapterPlanning"] == marker) {
                "Post-first-chapter planning stages froze different chapter evidence."
            }
        }
    }

    private suspend fun requireBootstrapProviderOpen(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        bootstrap: JsonObject,
    ) {
        val chapter = requireNotNull(database.libraryDao().findChapter(stage.targetId))
        require(chapter.bookId == job.bookId && chapter.chapterIndex == 1)
        require(bootstrap.string("bookId") == job.bookId)
        require(bootstrap.string("chapterId") == chapter.chapterId)
        require(bootstrap.int("chapterIndex") == 1)
        require(bootstrap.string("policyVersion") == FirstChapterProgressionPolicyV1.POLICY_VERSION)
        require(
            bootstrap.string("contractVersion") ==
                FirstChapterProgressionPolicyV1.FAST_LANE_CONTRACT_VERSION,
        )
        require(
            bootstrap.string("outputSchemaId") ==
                FirstChapterProgressionPolicyV1.FAST_LANE_OUTPUT_SCHEMA_ID,
        )
        require(
            bootstrap.int("requiredRoughChapterCount") ==
                FirstChapterProgressionPolicyV1.REQUIRED_ROUGH_CHAPTER_COUNT,
        )
        val seedStage = requireNotNull(database.generationDao().findStage(bootstrap.string("seedStageId")))
        val seedJob = requireNotNull(database.generationDao().findJob(seedStage.jobId))
        require(
            seedStage.phase == GenerationPhase.BUILD_STORY_SEED &&
                seedStage.targetType == GenerationTargetType.BOOK && seedStage.targetId == job.bookId &&
                seedStage.status == GenerationStageStatus.SUCCEEDED && seedJob.bookId == job.bookId,
        ) { "The first-chapter bootstrap lost its successful story-seed evidence." }
        val seedOutput = parseOutput(seedStage)
        require(seedOutput.string("outputSchemaId") == "story-seed.v1")
        require(seedOutput.string("rawOutputHash") == bootstrap.string("seedRawOutputHash"))
        require(seedOutput.string("contentHash") == bootstrap.string("seedContentHash"))
    }

    private suspend fun inspectFastLane(
        bookId: String,
        chapterId: String,
        seedStageId: String,
        bootstrapStageId: String,
    ): GateEvidence {
        val generation = database.generationDao()
        val seedStage = generation.findStage(seedStageId)
        val bootstrapStage = generation.findStage(bootstrapStageId)
        val seedReady = seedStage?.let { stage ->
            val job = generation.findJob(stage.jobId)
            stage.phase == GenerationPhase.BUILD_STORY_SEED &&
                stage.targetType == GenerationTargetType.BOOK && stage.targetId == bookId &&
                stage.status == GenerationStageStatus.SUCCEEDED && job?.bookId == bookId &&
                runCatching { parseOutput(stage).string("outputSchemaId") == "story-seed.v1" }.getOrDefault(false)
        } == true
        val seedOutput = seedStage?.takeIf { seedReady }?.let(::parseOutput)
        val bootstrapReady = bootstrapStage?.let { stage ->
            val job = generation.findJob(stage.jobId)
            val input = runCatching { parseObject(stage.inputSourcesJson, "Bootstrap input") }.getOrNull()
            val marker = input?.get("firstChapterBootstrap") as? JsonObject
            val output = runCatching { parseOutput(stage) }.getOrNull()
            val markerMatches = marker != null &&
                marker.optionalString("seedStageId") == seedStageId &&
                marker.optionalString("seedContentHash") == seedOutput?.optionalString("contentHash")
            val outputMatches = output != null &&
                output.optionalString("outputSchemaId") ==
                FirstChapterProgressionPolicyV1.FAST_LANE_OUTPUT_SCHEMA_ID &&
                output.optionalString("contractVersion") ==
                FirstChapterProgressionPolicyV1.FAST_LANE_CONTRACT_VERSION &&
                output.optionalString("seedContentHash") == seedOutput?.optionalString("contentHash") &&
                output.optionalInt("roughChapterCount") ==
                FirstChapterProgressionPolicyV1.REQUIRED_ROUGH_CHAPTER_COUNT
            stage.phase == GenerationPhase.BUILD_CHAPTER_PLAN &&
                stage.targetType == GenerationTargetType.CHAPTER && stage.targetId == chapterId &&
                stage.status == GenerationStageStatus.SUCCEEDED && job?.bookId == bookId &&
                markerMatches && outputMatches
        } == true
        val bootstrapOutput = bootstrapStage?.takeIf { bootstrapReady }?.let(::parseOutput)
        val adultGate = bootstrapOutput?.optionalBoolean("adultAndHardRuleGatePassed") == true
        return GateEvidence(
            policyEvidence = ChapterPlanningEvidence(
                storySeedReady = seedReady,
                firstChapterBootstrapReady = bootstrapReady,
                adultAndHardRuleGatePassed = adultGate,
                storyBibleReady = false,
                masterOutlineReady = false,
                targetChapterWindowReady = false,
                previousChapterCommitted = false,
                fullPlanningAdaptedToFirstChapter = false,
            ),
            fields = linkedMapOf(
                "mode" to JsonPrimitive(FirstChapterGenerationMode.FAST_LANE.name),
                "bookId" to JsonPrimitive(bookId),
                "chapterId" to JsonPrimitive(chapterId),
                "chapterIndex" to JsonPrimitive(1),
                "seedStageId" to JsonPrimitive(seedStageId),
                "seedContentHash" to (seedOutput?.optionalString("contentHash")?.let(::JsonPrimitive) ?: JsonNull),
                "bootstrapStageId" to JsonPrimitive(bootstrapStageId),
                "bootstrapContentHash" to (
                    bootstrapOutput?.optionalString("contentHash")?.let(::JsonPrimitive) ?: JsonNull
                ),
            ),
        )
    }

    private suspend fun inspectFullPlanning(
        bookId: String,
        chapterId: String,
        chapterIndex: Int,
        mode: FirstChapterGenerationMode,
    ): GateEvidence {
        val memory = database.memoryDao()
        val library = database.libraryDao()
        val head = memory.findMemoryHead(bookId)
        val bible = head?.currentBibleRevisionId?.let { memory.findBibleRevision(it) }
        val bibleReady = bible?.let { revision ->
            revision.bookId == bookId && revision.schemaVersion > 0 && HASH.matches(revision.contentHash) &&
                revision.generationStageId?.let { stageId ->
                    successfulRevisionStage(
                        stageId,
                        bookId,
                        GenerationPhase.BUILD_BIBLE,
                        "story-bible.v1",
                        revision.bibleRevisionId,
                        revision.contentHash,
                    )
                } == true
        } == true

        val currentOutline = head?.currentOutlineRevisionId?.let { memory.findOutlineRevision(it) }
        var cursor = currentOutline
        var master: OutlineRevisionEntity? = null
        var targetWindow: OutlineRevisionEntity? = null
        var visited = 0
        while (cursor != null && visited++ < MAX_OUTLINE_CHAIN_DEPTH) {
            require(cursor.bookId == bookId) { "Outline lineage crossed into another book." }
            val stage = cursor.generationStageId?.let { database.generationDao().findStage(it) }
            if (
                targetWindow == null && stage?.phase == GenerationPhase.BUILD_ARC_PLAN &&
                memory.findOutlineNodes(cursor.outlineRevisionId).any {
                    it.nodeType == OutlineNodeType.CHAPTER && it.plannedChapterIndex == chapterIndex
                }
            ) {
                targetWindow = cursor
            }
            if (cursor.revisionNo == 1) {
                master = cursor
                break
            }
            cursor = cursor.parentRevisionId?.let { memory.findOutlineRevision(it) }
        }
        val masterReady = master?.let { revision ->
            val stageId = revision.generationStageId ?: return@let false
            revision.parentRevisionId == null &&
                successfulRevisionStage(
                    stageId,
                    bookId,
                    GenerationPhase.BUILD_MASTER_OUTLINE,
                    "master-outline.v1",
                    revision.outlineRevisionId,
                    revision.contentHash,
                )
        } == true
        val windowReady = targetWindow?.let { revision ->
            val stageId = revision.generationStageId ?: return@let false
            successfulRevisionStage(
                stageId,
                bookId,
                GenerationPhase.BUILD_ARC_PLAN,
                "arc-plan.v1",
                revision.outlineRevisionId,
                revision.contentHash,
            )
        } == true
        val previousChapter = if (chapterIndex >= 2) {
            library.chaptersForBook(bookId).singleOrNull { it.chapterIndex == chapterIndex - 1 }
        } else {
            null
        }
        val previousVersion = previousChapter?.currentVersionId?.let { library.findChapterVersion(it) }
        val previousCommitted = chapterIndex == 1 || (
            previousChapter != null && previousVersion != null &&
                previousVersion.chapterId == previousChapter.chapterId
            )
        val firstChapter = library.chaptersForBook(bookId).singleOrNull { it.chapterIndex == 1 }
        val firstVersion = firstChapter?.currentVersionId?.let { library.findChapterVersion(it) }
        val adaptedToFirstChapter = if (chapterIndex < 2 || mode == FirstChapterGenerationMode.FULL_PLANNING) {
            true
        } else {
            bible?.generationStageId?.let { bibleStageId ->
                val bibleStage = database.generationDao().findStage(bibleStageId)
                val masterStage = master?.generationStageId?.let { database.generationDao().findStage(it) }
                firstVersion != null &&
                    bibleStage?.matchesPostFirstChapterBinding(firstChapter, firstVersion) == true &&
                    masterStage?.matchesPostFirstChapterBinding(firstChapter, firstVersion) == true
            } == true
        }
        return GateEvidence(
            policyEvidence = ChapterPlanningEvidence(
                storySeedReady = false,
                firstChapterBootstrapReady = false,
                adultAndHardRuleGatePassed = false,
                storyBibleReady = bibleReady,
                masterOutlineReady = masterReady,
                targetChapterWindowReady = windowReady,
                previousChapterCommitted = previousCommitted,
                fullPlanningAdaptedToFirstChapter = adaptedToFirstChapter,
            ),
            fields = linkedMapOf(
                "mode" to JsonPrimitive(mode.name),
                "bookId" to JsonPrimitive(bookId),
                "chapterId" to JsonPrimitive(chapterId),
                "chapterIndex" to JsonPrimitive(chapterIndex),
                "bibleRevisionId" to (bible?.bibleRevisionId?.let(::JsonPrimitive) ?: JsonNull),
                "bibleContentHash" to (bible?.contentHash?.let(::JsonPrimitive) ?: JsonNull),
                "masterOutlineRevisionId" to (master?.outlineRevisionId?.let(::JsonPrimitive) ?: JsonNull),
                "masterOutlineContentHash" to (master?.contentHash?.let(::JsonPrimitive) ?: JsonNull),
                "currentOutlineRevisionId" to (
                    currentOutline?.outlineRevisionId?.let(::JsonPrimitive) ?: JsonNull
                ),
                "currentOutlineContentHash" to (currentOutline?.contentHash?.let(::JsonPrimitive) ?: JsonNull),
                "targetWindowRevisionId" to (targetWindow?.outlineRevisionId?.let(::JsonPrimitive) ?: JsonNull),
                "targetWindowContentHash" to (targetWindow?.contentHash?.let(::JsonPrimitive) ?: JsonNull),
                "previousChapterId" to (previousChapter?.chapterId?.let(::JsonPrimitive) ?: JsonNull),
                "previousChapterVersionId" to (
                    previousVersion?.chapterVersionId?.let(::JsonPrimitive) ?: JsonNull
                ),
                "previousChapterContentHash" to (previousVersion?.contentHash?.let(::JsonPrimitive) ?: JsonNull),
                "firstChapterVersionId" to (firstVersion?.chapterVersionId?.let(::JsonPrimitive) ?: JsonNull),
                "firstChapterContentHash" to (firstVersion?.contentHash?.let(::JsonPrimitive) ?: JsonNull),
                "seedStageId" to JsonNull,
                "bootstrapStageId" to JsonNull,
            ),
        )
    }

    private suspend fun successfulRevisionStage(
        stageId: String,
        bookId: String,
        phase: GenerationPhase,
        schemaId: String,
        objectId: String,
        contentHash: String,
    ): Boolean {
        val generation = database.generationDao()
        val stage = generation.findStage(stageId) ?: return false
        val job = generation.findJob(stage.jobId) ?: return false
        if (stage.phase != phase || stage.status != GenerationStageStatus.SUCCEEDED || job.bookId != bookId) {
            return false
        }
        val output = runCatching { parseOutput(stage) }.getOrNull() ?: return false
        val persistedObjectId = output.optionalString("committedObjectId")
            ?: output.optionalString("outlineRevisionId")
        return output.optionalString("outputSchemaId") == schemaId &&
            persistedObjectId == objectId &&
            output.optionalString("contentHash") == contentHash
    }

    private fun GenerationStageEntity.matchesPostFirstChapterBinding(
        firstChapter: app.zhijuan.core.database.library.ChapterEntity?,
        firstVersion: app.zhijuan.core.database.library.ChapterVersionEntity,
    ): Boolean {
        val root = runCatching { parseObject(inputSourcesJson, "Post-first-chapter planning input") }
            .getOrNull() ?: return false
        val marker = root["postFirstChapterPlanning"] as? JsonObject ?: return false
        return firstChapter != null &&
            marker.optionalString("chapterId") == firstChapter.chapterId &&
            marker.optionalString("chapterVersionId") == firstVersion.chapterVersionId &&
            marker.optionalString("chapterContentHash") == firstVersion.contentHash
    }

    private fun parseOutput(stage: GenerationStageEntity): JsonObject = parseObject(
        requireNotNull(stage.outputReferenceJson) { "Successful stage output evidence is missing." },
        "Generation output reference",
    )

    private data class GateEvidence(
        val policyEvidence: ChapterPlanningEvidence,
        val fields: LinkedHashMap<String, kotlinx.serialization.json.JsonElement>,
    ) {
        fun jsonWithHash(): JsonObject {
            val base = JsonObject(
                linkedMapOf(
                    "schemaVersion" to JsonPrimitive(1),
                    "policyVersion" to JsonPrimitive(FirstChapterProgressionPolicyV1.POLICY_VERSION),
                ) + fields,
            )
            return JsonObject(base + ("evidenceHash" to JsonPrimitive(sha256(base.toString()))))
        }
    }

    private fun blocked(reason: ChapterProgressionBlockReason) = ChapterProgressionAuthorization.Blocked(reason)

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
        const val MAX_OUTLINE_CHAIN_DEPTH = 2_000
        val REMOTE_CHAPTER_PHASES = setOf(
            GenerationPhase.BUILD_CHAPTER_PLAN,
            GenerationPhase.DRAFT_CHAPTER,
            GenerationPhase.CHECK_CONSISTENCY,
            GenerationPhase.REVISE_CHAPTER,
        )
    }
}

private val STRICT_JSON = Json { isLenient = false }

private fun parseObject(value: String, label: String): JsonObject = runCatching {
    STRICT_JSON.parseToJsonElement(value) as JsonObject
}.getOrElse { throw IllegalArgumentException("$label is invalid JSON.") }

private fun JsonObject.string(key: String): String =
    optionalString(key) ?: throw IllegalArgumentException("Required evidence field is missing: $key")
private fun JsonObject.int(key: String): Int =
    optionalInt(key) ?: throw IllegalArgumentException("Required evidence field is missing: $key")
private fun JsonObject.optionalString(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
private fun JsonObject.optionalInt(key: String): Int? =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
private fun JsonObject.optionalBoolean(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
