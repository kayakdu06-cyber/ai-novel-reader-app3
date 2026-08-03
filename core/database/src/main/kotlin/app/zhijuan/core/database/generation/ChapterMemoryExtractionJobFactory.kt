package app.zhijuan.core.database.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.PromptBundleCatalogV1
import app.zhijuan.core.task.StageIdempotencyKey
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class ChapterMemoryExtractionSourceV1(
    val chapterVersionId: String,
    val chapterContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
) {
    init {
        require(IDENTIFIER.matches(chapterVersionId) && IDENTIFIER.matches(chapterId))
        require(HASH.matches(chapterContentHash))
        require(chapterIndex in 1..10_000)
    }
}

data class ChapterMemoryExtractionJobSpec(
    val jobId: String,
    val stageId: String,
    val bookId: String,
    val userIntentJson: String,
    val budgetSnapshotJson: String,
    val source: ChapterMemoryExtractionSourceV1,
    val maxAttempts: Int = 2,
    val createdAt: Long,
)

object ChapterMemoryExtractionJobFactory {
    const val SOURCE_POLICY_VERSION = "zhijuan.chapter-memory-source.v1"
    const val OUTPUT_SCHEMA_ID = "chapter-memory.v1"

    fun create(spec: ChapterMemoryExtractionJobSpec): GenerationJobSetup {
        require(listOf(spec.jobId, spec.stageId, spec.bookId).all(IDENTIFIER::matches))
        require(spec.maxAttempts in 1..2) { "Chapter-memory extraction permits at most one format repair." }
        require(spec.createdAt >= 0L)
        val inputSources = inputSources(spec.source)
        val inputVersionHash = sourceInputHash(inputSources)
        return GenerationJobSetup(
            jobId = spec.jobId,
            bookId = spec.bookId,
            jobType = GenerationJobType.CONTINUE_BOOK,
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

    internal fun parseAndVerify(stage: GenerationStageEntity): ChapterMemoryExtractionSourceV1 {
        require(stage.phase == GenerationPhase.EXTRACT_MEMORY)
        require(stage.targetType == GenerationTargetType.CHAPTER)
        val root = runCatching { STRICT_JSON.parseToJsonElement(stage.inputSourcesJson) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Chapter-memory source binding is invalid JSON.") }
        require(root.keys == ROOT_KEYS)
        require(root.int("schemaVersion") == 1)
        require(root.string("sourcePolicyVersion") == SOURCE_POLICY_VERSION)
        require(root.string("promptBundleVersion") == PromptBundleCatalogV1.BUNDLE_VERSION)
        require(root.string("outputSchemaId") == OUTPUT_SCHEMA_ID)
        val sourceObject = root["chapterMemorySource"] as? JsonObject
            ?: throw IllegalArgumentException("Chapter-memory source binding is missing.")
        require(sourceObject.keys == SOURCE_KEYS)
        val source = ChapterMemoryExtractionSourceV1(
            chapterVersionId = sourceObject.string("chapterVersionId"),
            chapterContentHash = sourceObject.string("chapterContentHash"),
            chapterId = sourceObject.string("chapterId"),
            chapterIndex = sourceObject.int("chapterIndex"),
        )
        require(source.chapterId == stage.targetId)
        require(stage.inputVersionHash == sourceInputHash(stage.inputSourcesJson)) {
            "Chapter-memory input hash does not match its frozen source binding."
        }
        return source
    }

    internal fun inputSources(source: ChapterMemoryExtractionSourceV1): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourcePolicyVersion" to JsonPrimitive(SOURCE_POLICY_VERSION),
            "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
            "outputSchemaId" to JsonPrimitive(OUTPUT_SCHEMA_ID),
            "chapterMemorySource" to JsonObject(
                linkedMapOf(
                    "chapterVersionId" to JsonPrimitive(source.chapterVersionId),
                    "chapterContentHash" to JsonPrimitive(source.chapterContentHash),
                    "chapterId" to JsonPrimitive(source.chapterId),
                    "chapterIndex" to JsonPrimitive(source.chapterIndex),
                ),
            ),
        ),
    ).toString()

    private fun sourceInputHash(inputSources: String): String = sha256(
        listOf(SOURCE_POLICY_VERSION, OUTPUT_SCHEMA_ID, inputSources).joinToString("\u0000"),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw IllegalArgumentException("Chapter-memory source field is missing: $key")

    private fun JsonObject.int(key: String): Int =
        (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw IllegalArgumentException("Chapter-memory source field is missing: $key")

    private val STRICT_JSON = Json { isLenient = false }
    private val ROOT_KEYS = setOf(
        "schemaVersion", "sourcePolicyVersion", "promptBundleVersion", "outputSchemaId", "chapterMemorySource",
    )
    private val SOURCE_KEYS = setOf("chapterVersionId", "chapterContentHash", "chapterId", "chapterIndex")
}

internal class ChapterMemoryExtractionSourceGuard(
    private val database: ZhijuanDatabase,
) {
    suspend fun requireProviderOpenAllowedIfBound(
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
    ) {
        if (stage.phase != GenerationPhase.EXTRACT_MEMORY) return
        if (ChapterCandidateStageBindingV1.isBound(stage, ChapterCandidateArtifactRoleV1.MEMORY)) return
        if (ChapterTrackingProjectionJobFactory.isBound(stage)) return
        val source = ChapterMemoryExtractionJobFactory.parseAndVerify(stage)
        val library = database.libraryDao()
        val chapter = requireNotNull(library.findChapter(source.chapterId)) {
            "Chapter-memory source chapter no longer exists."
        }
        val version = requireNotNull(library.findChapterVersion(source.chapterVersionId)) {
            "Chapter-memory source version no longer exists."
        }
        require(
            job.bookId == chapter.bookId && version.chapterId == chapter.chapterId &&
                chapter.currentVersionId == version.chapterVersionId &&
                chapter.chapterIndex == source.chapterIndex && version.contentHash == source.chapterContentHash,
        ) { "Chapter-memory source version is no longer the frozen current chapter." }
    }
}

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
