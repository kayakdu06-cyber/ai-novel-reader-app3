package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import app.zhijuan.core.task.FirstChapterProgressionPolicyV1
import app.zhijuan.core.task.GenerationJobStateMachine
import app.zhijuan.core.task.GenerationStageStateMachine
import app.zhijuan.core.task.JobEvent
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageEvent
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class FirstChapterFastLaneCommitDraft(
    val schemaId: String,
    val canonicalJson: String,
    val contentHash: String,
    val seedContentHash: String,
    val characterCount: Int,
    val usage: FinalUsageCommit = FinalUsageCommit.UNKNOWN,
    val committedAt: Long,
) {
    override fun toString(): String =
        "FirstChapterFastLaneCommitDraft(characterCount=$characterCount, content=redacted)"
}

data class FirstChapterFastLaneCommitResult(
    val stageId: String,
    val chapterId: String,
    val jobCompleted: Boolean,
    val replayed: Boolean,
) {
    override fun toString(): String =
        "FirstChapterFastLaneCommitResult(jobCompleted=$jobCompleted, replayed=$replayed, evidence=redacted)"
}

class FirstChapterFastLaneCommitRepository(
    private val database: ZhijuanDatabase,
    private val artifactStore: AndroidProtectedArtifactStore,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun commit(
        permit: ValidatedOutputCommitPermit,
        draft: FirstChapterFastLaneCommitDraft,
    ): FirstChapterFastLaneCommitResult {
        validateDraft(draft)
        require(draft.committedAt >= permit.validatedAt)
        if (database.generationDao().findStage(permit.stageId)?.status != GenerationStageStatus.SUCCEEDED) {
            verifyValidatedArtifact(permit)
        }
        val outputReference = outputReferenceJson(permit, draft)
        return database.withTransaction {
            val generation = database.generationDao()
            val library = database.libraryDao()
            val stage = requireNotNull(generation.findStage(permit.stageId)) {
                "Validated first-chapter bootstrap stage no longer exists."
            }
            val job = requireNotNull(generation.findJob(stage.jobId)) {
                "Owning first-chapter bootstrap job no longer exists."
            }
            val attempt = requireNotNull(generation.findAttempt(permit.attemptId)) {
                "Validated first-chapter bootstrap attempt no longer exists."
            }
            val chapter = requireNotNull(library.findChapter(stage.targetId)) {
                "First-chapter bootstrap target no longer exists."
            }
            val book = requireNotNull(library.findBook(chapter.bookId)) {
                "First-chapter bootstrap book no longer exists."
            }
            require(
                stage.phase == GenerationPhase.BUILD_CHAPTER_PLAN &&
                    stage.targetType == GenerationTargetType.CHAPTER &&
                    chapter.chapterIndex == 1 && job.bookId == book.bookId &&
                    job.promptBundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION,
            ) { "First-chapter bootstrap stage binding is invalid." }
            require(book.status in setOf(BookStatus.DRAFT, BookStatus.GENERATING))
            require(
                attempt.stageId == stage.stageId && attempt.status == RequestAttemptStatus.SUCCEEDED &&
                    attempt.standardErrorCode == null && attempt.outputHash == permit.rawOutputHash &&
                    attempt.streamDraftRef == permit.artifactRefId &&
                    generation.attemptsForStage(stage.stageId).lastOrNull()?.attemptId == attempt.attemptId,
            ) { "First-chapter bootstrap validation evidence changed before commit." }
            require(generation.findUsageForAttempt(attempt.attemptId)?.bookId == book.bookId)
            requireBootstrapInput(stage, book.bookId, chapter.chapterId, draft)

            if (stage.status == GenerationStageStatus.SUCCEEDED) {
                require(stage.outputReferenceJson == outputReference) {
                    "Completed first-chapter bootstrap does not match the replayed payload."
                }
                generation.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.committedAt))
                return@withTransaction FirstChapterFastLaneCommitResult(
                    stage.stageId,
                    chapter.chapterId,
                    job.status == GenerationJobStatus.COMPLETED,
                    true,
                )
            }

            require(stage.status == GenerationStageStatus.COMMITTING)
            require(
                job.status in setOf(GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING) &&
                    job.currentStageId == stage.stageId,
            ) { "First-chapter bootstrap job is not running the validated stage." }
            requireActiveLease(stage, permit.leaseToken, draft.committedAt)
            generation.recordUsage(attempt.attemptId, draft.usage.toFinalUpdate(draft.committedAt))
            check(
                GenerationStageStateMachine.transition(stage.status, StageEvent.COMMIT_SUCCEEDED) ==
                    GenerationStageStatus.SUCCEEDED,
            )
            if (
                generation.compareAndCommitStageOutput(
                    stageId = stage.stageId,
                    leaseOwnerId = permit.leaseToken.ownerId,
                    leaseAcquiredAt = permit.leaseToken.acquiredAt,
                    outputReferenceJson = outputReference,
                    updatedAt = draft.committedAt,
                ) != 1
            ) {
                throw StaleGenerationStateException("First-chapter bootstrap commit lost its stage lease.")
            }
            require(generation.countNonSucceededStages(job.jobId) == 0)
            check(
                GenerationJobStateMachine.transition(job.status, JobEvent.ALL_STAGES_COMPLETED) ==
                    GenerationJobStatus.COMPLETED,
            )
            if (
                generation.compareAndCompleteJobAfterStage(
                    jobId = job.jobId,
                    expectedCurrentStageId = stage.stageId,
                    updatedAt = draft.committedAt,
                ) != 1
            ) {
                throw StaleGenerationStateException("First-chapter bootstrap job changed during commit.")
            }
            FirstChapterFastLaneCommitResult(stage.stageId, chapter.chapterId, true, false)
        }
    }

    private suspend fun requireBootstrapInput(
        stage: GenerationStageEntity,
        bookId: String,
        chapterId: String,
        draft: FirstChapterFastLaneCommitDraft,
    ) {
        val root = parseObject(stage.inputSourcesJson, "First-chapter bootstrap input")
        val input = root["firstChapterBootstrap"]?.jsonObject
            ?: throw IllegalArgumentException("First-chapter bootstrap input evidence is missing.")
        require(input.string("policyVersion") == FirstChapterProgressionPolicyV1.POLICY_VERSION)
        require(input.string("contractVersion") == FirstChapterProgressionPolicyV1.FAST_LANE_CONTRACT_VERSION)
        require(input.string("outputSchemaId") == draft.schemaId)
        require(input.string("bookId") == bookId && input.string("chapterId") == chapterId)
        require(input.int("chapterIndex") == 1)
        require(
            input.int("requiredRoughChapterCount") ==
                FirstChapterProgressionPolicyV1.REQUIRED_ROUGH_CHAPTER_COUNT,
        )
        require(input.string("seedContentHash") == draft.seedContentHash)
        val seedStage = requireNotNull(database.generationDao().findStage(input.string("seedStageId"))) {
            "Frozen story-seed stage is missing."
        }
        val seedJob = requireNotNull(database.generationDao().findJob(seedStage.jobId))
        require(
            seedStage.phase == GenerationPhase.BUILD_STORY_SEED &&
                seedStage.targetType == GenerationTargetType.BOOK && seedStage.targetId == bookId &&
                seedStage.status == GenerationStageStatus.SUCCEEDED && seedJob.bookId == bookId,
        ) { "Frozen story-seed stage is not a successful seed for this book." }
        val output = parseObject(
            requireNotNull(seedStage.outputReferenceJson) { "Frozen story-seed output reference is missing." },
            "Story-seed output reference",
        )
        require(output.string("outputSchemaId") == "story-seed.v1")
        require(output.string("rawOutputHash") == input.string("seedRawOutputHash"))
        require(output.string("contentHash") == draft.seedContentHash)
        verifyBootstrapAgainstSeed(output, draft)
    }

    private suspend fun verifyBootstrapAgainstSeed(
        seedOutput: JsonObject,
        draft: FirstChapterFastLaneCommitDraft,
    ) {
        val seedAttempt = requireNotNull(
            database.generationDao().findAttempt(seedOutput.string("attemptId")),
        ) { "Story-seed attempt evidence is missing." }
        val seedArtifactRef = requireNotNull(seedAttempt.streamDraftRef) {
            "Story-seed protected output reference is missing."
        }
        val seedJson = artifactStore.readBytes(
            seedArtifactRef,
            ProtectedArtifactType.STREAM_DRAFT,
            MAX_OUTPUT_BYTES,
        ).use { lease ->
            require(lease.withBytes(::sha256) == seedOutput.string("rawOutputHash"))
            lease.withBytes { bytes -> parseObject(bytes.decodeToString(), "Story-seed protected payload") }
        }
        val bootstrapJson = parseObject(draft.canonicalJson, "First-chapter bootstrap payload")
        require(bootstrapJson.string("seedContentHash") == sha256Utf8(seedJson.toString()))
        require(bootstrapJson.string("endingDirection") == seedJson.string("endingDirection")) {
            "First-chapter bootstrap changed the frozen ending direction."
        }
        val seedCharacters = seedJson.objects("characters").associateBy { it.string("entityId") }
        val bootstrapCharacters = bootstrapJson.objects("characters").associateBy { it.string("entityId") }
        require(seedCharacters.keys == bootstrapCharacters.keys) {
            "First-chapter bootstrap changed the frozen character set."
        }
        seedCharacters.forEach { (entityId, seedCharacter) ->
            val bootstrapCharacter = requireNotNull(bootstrapCharacters[entityId])
            listOf("ageYears", "adultStatus", "realIdentifiablePerson", "intimacyRole").forEach { key ->
                require(seedCharacter.getValue(key) == bootstrapCharacter.getValue(key)) {
                    "First-chapter bootstrap changed a frozen character fact."
                }
            }
            if (bootstrapCharacter.boolean("intimacyRole")) {
                require(
                    bootstrapCharacter.string("adultStatus") == "CONFIRMED_ADULT" &&
                        bootstrapCharacter.int("ageYears") >= 18 &&
                        !bootstrapCharacter.boolean("realIdentifiablePerson"),
                ) { "First-chapter bootstrap failed the adult fictional-character gate." }
            }
        }
        require(
            bootstrapJson.objects("roughChapters").map { it.int("chapterIndex") } == listOf(1, 2, 3),
        ) { "First-chapter bootstrap must preserve exactly the first three rough chapter plans." }
    }

    private fun validateDraft(draft: FirstChapterFastLaneCommitDraft) {
        require(draft.schemaId == FirstChapterProgressionPolicyV1.FAST_LANE_OUTPUT_SCHEMA_ID)
        require(HASH.matches(draft.contentHash) && HASH.matches(draft.seedContentHash))
        require(draft.characterCount in 1..16)
        require(draft.committedAt >= 0L)
        require(draft.canonicalJson.toByteArray(Charsets.UTF_8).size in 2..MAX_OUTPUT_BYTES)
        parseObject(draft.canonicalJson, "First-chapter bootstrap payload")
        require(sha256Utf8(draft.canonicalJson) == draft.contentHash)
    }

    private fun requireActiveLease(
        stage: GenerationStageEntity,
        token: GenerationLeaseToken,
        operationAt: Long,
    ) {
        require(stage.leaseOwnerId == token.ownerId && stage.leaseAcquiredAt == token.acquiredAt)
        val heartbeatAt = requireNotNull(stage.leaseHeartbeatAt)
        require(operationAt >= stage.updatedAt && operationAt >= heartbeatAt)
        if (leasePolicy.isExpired(heartbeatAt, operationAt)) {
            throw StaleGenerationStateException("Stage lease expired before first-chapter bootstrap commit.")
        }
    }

    private fun verifyValidatedArtifact(permit: ValidatedOutputCommitPermit) {
        artifactStore.readBytes(
            permit.artifactRefId,
            ProtectedArtifactType.STREAM_DRAFT,
            MAX_OUTPUT_BYTES,
        ).use { lease ->
            require(lease.descriptor.revision == permit.artifactRevision)
            require(lease.withBytes(::sha256) == permit.rawOutputHash)
        }
    }

    private fun outputReferenceJson(
        permit: ValidatedOutputCommitPermit,
        draft: FirstChapterFastLaneCommitDraft,
    ): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "outputSchemaId" to JsonPrimitive(draft.schemaId),
            "contractVersion" to JsonPrimitive(FirstChapterProgressionPolicyV1.FAST_LANE_CONTRACT_VERSION),
            "attemptId" to JsonPrimitive(permit.attemptId),
            "rawOutputHash" to JsonPrimitive(permit.rawOutputHash),
            "contentHash" to JsonPrimitive(draft.contentHash),
            "seedContentHash" to JsonPrimitive(draft.seedContentHash),
            "roughChapterCount" to JsonPrimitive(
                FirstChapterProgressionPolicyV1.REQUIRED_ROUGH_CHAPTER_COUNT,
            ),
            "adultAndHardRuleGatePassed" to JsonPrimitive(true),
        ),
    ).toString()

    private fun FinalUsageCommit.toFinalUpdate(updatedAt: Long) = UsageUpdate(
        source = source,
        status = UsageLedgerStatus.FINAL,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedTokens = cachedTokens,
        reasoningTokens = reasoningTokens,
        totalTokens = totalTokens,
        currency = currency,
        estimatedCostMicros = estimatedCostMicros,
        priceCatalogVersion = priceCatalogVersion,
        updatedAt = updatedAt,
    )

    private fun parseObject(value: String, label: String): JsonObject = runCatching {
        JSON.parseToJsonElement(value).jsonObject
    }.getOrElse { throw IllegalArgumentException("$label is invalid JSON.") }

    private fun JsonObject.string(key: String): String = jsonPrimitive(key).content
    private fun JsonObject.int(key: String): Int = jsonPrimitive(key).int
    private fun JsonObject.boolean(key: String): Boolean = jsonPrimitive(key).boolean
    private fun JsonObject.objects(key: String): List<JsonObject> =
        (requireNotNull(get(key)) as? JsonArray)?.map { element -> element as JsonObject }
            ?: throw IllegalArgumentException("Required evidence array is missing: $key")
    private fun JsonObject.jsonPrimitive(key: String) =
        requireNotNull(get(key)) { "Required evidence field is missing: $key" }.jsonPrimitive

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    private fun sha256Utf8(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private companion object {
        const val MAX_OUTPUT_BYTES = 512 * 1_024
        val HASH = Regex("[0-9a-f]{64}")
        val JSON = Json { isLenient = false }
    }
}
