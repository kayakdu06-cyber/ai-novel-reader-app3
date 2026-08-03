package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.MasterOutlinePlanningCommitDraft
import app.zhijuan.core.database.generation.StoryBiblePlanningCommitDraft
import app.zhijuan.core.database.generation.StorySeedPlanningCommitDraft
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.OutlineNodeEntity
import app.zhijuan.core.database.memory.OutlineRevisionEntity
import app.zhijuan.core.database.memory.StoryBibleRevisionEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.model.RevisionSource
import app.zhijuan.core.model.StoryEntityType
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class InitialPlanningPersistenceIds(
    val bookId: String,
    val bibleRevisionId: String,
    val outlineRevisionId: String,
    val seedNextStageId: String,
    val bibleStageId: String,
    val bibleNextStageId: String,
    val outlineStageId: String,
)

data class InitialPlanningCommitDrafts(
    val seed: StorySeedPlanningCommitDraft,
    val bible: StoryBiblePlanningCommitDraft,
    val outline: MasterOutlinePlanningCommitDraft,
)

object InitialPlanningPersistenceMapper {
    fun map(
        validated: InitialPlanningBundleValidationResult.Valid,
        ids: InitialPlanningPersistenceIds,
        seedCommittedAt: Long,
        bibleCommittedAt: Long,
        outlineCommittedAt: Long,
    ): InitialPlanningCommitDrafts {
        require(
            seedCommittedAt >= 0L && bibleCommittedAt >= seedCommittedAt &&
                outlineCommittedAt >= bibleCommittedAt,
        ) { "Initial planning commit times must be monotonic." }
        requireIdentifiers(
            ids.bookId,
            ids.bibleRevisionId,
            ids.outlineRevisionId,
            ids.seedNextStageId,
            ids.bibleStageId,
            ids.bibleNextStageId,
            ids.outlineStageId,
        )
        require(ids.seedNextStageId == ids.bibleStageId && ids.bibleNextStageId == ids.outlineStageId) {
            "Initial planning stage links do not form the frozen seed-to-Bible-to-outline chain."
        }
        return InitialPlanningCommitDrafts(
            seed = storySeed(
                seed = validated.seed,
                expectedTargetChapterCount = validated.outline.targetChapterCount,
                nextStageId = ids.seedNextStageId,
                committedAt = seedCommittedAt,
            ),
            bible = storyBible(
                seed = validated.seed,
                bible = validated.bible,
                bookId = ids.bookId,
                bibleRevisionId = ids.bibleRevisionId,
                bibleStageId = ids.bibleStageId,
                nextStageId = ids.bibleNextStageId,
                committedAt = bibleCommittedAt,
            ),
            outline = masterOutline(
                bible = validated.bible,
                outline = validated.outline,
                expectedTargetChapterCount = validated.seed.targetChapterCount,
                bookId = ids.bookId,
                outlineRevisionId = ids.outlineRevisionId,
                outlineStageId = ids.outlineStageId,
                committedAt = outlineCommittedAt,
            ),
        )
    }

    fun storySeed(
        seed: StorySeedV1,
        expectedTargetChapterCount: Int,
        nextStageId: String,
        committedAt: Long,
    ): StorySeedPlanningCommitDraft {
        requireIdentifiers(nextStageId)
        require(committedAt >= 0L)
        require(InitialPlanningBundleValidator.validateSeed(seed, expectedTargetChapterCount).isEmpty()) {
            "Story seed failed its frozen target or character-safety dependencies."
        }
        return StorySeedPlanningCommitDraft(
            schemaId = StorySeedOutputContractV1.schemaId,
            canonicalJson = seed.canonicalJson,
            contentHash = seed.contentHash,
            nextStageId = nextStageId,
            committedAt = committedAt,
        )
    }

    fun storyBible(
        seed: StorySeedV1,
        bible: StoryBibleV1,
        bookId: String,
        bibleRevisionId: String,
        bibleStageId: String,
        nextStageId: String,
        committedAt: Long,
    ): StoryBiblePlanningCommitDraft {
        requireIdentifiers(bookId, bibleRevisionId, bibleStageId, nextStageId)
        require(committedAt >= 0L)
        require(InitialPlanningBundleValidator.validateSeed(seed, seed.targetChapterCount).isEmpty()) {
            "Story seed no longer satisfies character-safety dependencies."
        }
        require(InitialPlanningBundleValidator.validateBible(seed, bible).isEmpty()) {
            "Story Bible does not preserve the committed seed dependencies."
        }

        val entityIds = bible.characters.associate { character ->
            character.entityId to stableId("entity", bookId, character.entityId)
        }
        val characters = bible.characters.map { character ->
            StoryEntity(
                entityId = entityIds.getValue(character.entityId),
                bookId = bookId,
                entityType = StoryEntityType.CHARACTER,
                canonicalName = character.canonicalName,
                aliasesJson = JsonArray(character.aliases.map(::JsonPrimitive)).toString(),
                stableDefinitionJson = JsonObject(
                    linkedMapOf(
                        "schemaVersion" to JsonPrimitive(1),
                        "localEntityId" to JsonPrimitive(character.entityId),
                        "realIdentifiablePerson" to JsonPrimitive(character.realIdentifiablePerson),
                        "storyRole" to JsonPrimitive(character.storyRole),
                        "stableTraits" to JsonArray(character.stableTraits.map(::JsonPrimitive)),
                        "goals" to JsonArray(character.goals.map(::JsonPrimitive)),
                        "boundaries" to JsonArray(character.boundaries.map(::JsonPrimitive)),
                    ),
                ).toString(),
                adultStatus = character.adultStatus,
                ageYears = character.ageYears,
                sourceBibleRevisionId = bibleRevisionId,
                createdAt = committedAt,
                updatedAt = committedAt,
                archivedAt = null,
            )
        }
        val worldRuleFacts = bible.worldRules.map { rule ->
            canonFact(
                id = stableId("fact", bookId, rule.ruleId),
                bookId = bookId,
                entityId = null,
                text = rule.text,
                localId = rule.ruleId,
                kind = "world_rule",
                bibleRevisionId = bibleRevisionId,
                createdAt = committedAt,
            )
        }
        val characterFacts = bible.hardFacts.map { fact ->
            canonFact(
                id = stableId("fact", bookId, fact.factId),
                bookId = bookId,
                entityId = fact.entityId?.let(entityIds::getValue),
                text = fact.text,
                localId = fact.factId,
                kind = "hard_fact",
                bibleRevisionId = bibleRevisionId,
                createdAt = committedAt,
            )
        }
        val bibleRevision = StoryBibleRevisionEntity(
            bibleRevisionId = bibleRevisionId,
            bookId = bookId,
            revisionNo = 1,
            parentRevisionId = null,
            source = RevisionSource.AI_GENERATED,
            schemaVersion = 1,
            contentControlSchemaVersion = 1,
            payloadJson = bible.canonicalJson,
            contentHash = bible.contentHash,
            generationStageId = bibleStageId,
            createdAt = committedAt,
        )
        return StoryBiblePlanningCommitDraft(
            schemaId = StoryBibleOutputContractV1.schemaId,
            revision = bibleRevision,
            characters = characters,
            hardFacts = worldRuleFacts + characterFacts,
            nextStageId = nextStageId,
            committedAt = committedAt,
        )
    }

    fun masterOutline(
        bible: StoryBibleV1,
        outline: MasterOutlineV1,
        expectedTargetChapterCount: Int,
        bookId: String,
        outlineRevisionId: String,
        outlineStageId: String,
        committedAt: Long,
    ): MasterOutlinePlanningCommitDraft {
        requireIdentifiers(bookId, outlineRevisionId, outlineStageId)
        require(committedAt >= 0L)
        require(
            InitialPlanningBundleValidator.validateOutline(
                bible,
                outline,
                expectedTargetChapterCount,
            ).isEmpty(),
        ) { "Master outline does not preserve the committed Bible or chapter target." }
        val outlineRevision = OutlineRevisionEntity(
            outlineRevisionId = outlineRevisionId,
            bookId = bookId,
            revisionNo = 1,
            parentRevisionId = null,
            source = RevisionSource.AI_GENERATED,
            schemaVersion = 1,
            summaryJson = outline.canonicalJson,
            contentHash = outline.contentHash,
            generationStageId = outlineStageId,
            createdAt = committedAt,
        )
        val outlineRoot = OutlineNodeEntity(
            outlineNodeId = stableId("outline-root", bookId, outlineRevisionId),
            outlineRevisionId = outlineRevisionId,
            parentNodeId = null,
            nodeType = OutlineNodeType.BOOK,
            orderKey = 0L,
            plannedChapterIndex = null,
            title = outline.title,
            planJson = outline.canonicalJson,
            contentHash = outline.contentHash,
            createdAt = committedAt,
        )
        return MasterOutlinePlanningCommitDraft(
            schemaId = MasterOutlineOutputContractV1.schemaId,
            revision = outlineRevision,
            nodes = listOf(outlineRoot),
            committedAt = committedAt,
        )
    }

    private fun requireIdentifiers(vararg values: String) {
        require(values.all(IDENTIFIER::matches)) {
            "Initial planning persistence identifiers are invalid."
        }
    }

    private fun canonFact(
        id: String,
        bookId: String,
        entityId: String?,
        text: String,
        localId: String,
        kind: String,
        bibleRevisionId: String,
        createdAt: Long,
    ) = CanonFactEntity(
        canonFactId = id,
        bookId = bookId,
        entityId = entityId,
        factText = text,
        factPayloadJson = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "kind" to JsonPrimitive(kind),
                "localFactId" to JsonPrimitive(localId),
                "entityId" to (entityId?.let(::JsonPrimitive) ?: JsonNull),
            ),
        ).toString(),
        canonLevel = CanonLevel.HARD_CANON,
        scopeJson = JsonObject(mapOf("scope" to JsonPrimitive("book"))).toString(),
        sourceChapterVersionId = null,
        sourceBibleRevisionId = bibleRevisionId,
        validFromStoryOrder = null,
        validToStoryOrder = null,
        conflictGroupId = null,
        status = DerivedDataStatus.VALID,
        createdAt = createdAt,
    )

    private fun stableId(kind: String, bookId: String, localId: String): String =
        "$kind.${sha256("$bookId\u0000$localId").take(32)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
}
