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

internal object InitialPlanningPersistenceMapper {
    fun storySeed(
        seed: StorySeedV1,
        expectedTargetChapterCount: Int,
        nextStageId: String,
        committedAt: Long,
    ): StorySeedPlanningCommitDraft {
        requireIdentifier(nextStageId)
        require(InitialPlanningBundleValidator.validateSeed(seed, expectedTargetChapterCount).isEmpty())
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
        listOf(bookId, bibleRevisionId, bibleStageId, nextStageId).forEach(::requireIdentifier)
        require(InitialPlanningBundleValidator.validateSeed(seed, seed.targetChapterCount).isEmpty())
        require(InitialPlanningBundleValidator.validateBible(seed, bible).isEmpty())
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
                stableDefinitionJson = JsonObject(linkedMapOf(
                    "schemaVersion" to JsonPrimitive(1),
                    "localEntityId" to JsonPrimitive(character.entityId),
                    "realIdentifiablePerson" to JsonPrimitive(character.realIdentifiablePerson),
                    "storyRole" to JsonPrimitive(character.storyRole),
                    "stableTraits" to JsonArray(character.stableTraits.map(::JsonPrimitive)),
                    "goals" to JsonArray(character.goals.map(::JsonPrimitive)),
                    "boundaries" to JsonArray(character.boundaries.map(::JsonPrimitive)),
                )).toString(),
                adultStatus = character.adultStatus,
                ageYears = character.ageYears,
                sourceBibleRevisionId = bibleRevisionId,
                createdAt = committedAt,
                updatedAt = committedAt,
                archivedAt = null,
            )
        }
        val worldRules = bible.worldRules.map { rule ->
            canonFact(
                id = stableId("fact", bookId, rule.ruleId), bookId = bookId, entityId = null,
                text = rule.text, localId = rule.ruleId, kind = "world_rule",
                bibleRevisionId = bibleRevisionId, createdAt = committedAt,
            )
        }
        val hardFacts = bible.hardFacts.map { fact ->
            canonFact(
                id = stableId("fact", bookId, fact.factId), bookId = bookId,
                entityId = fact.entityId?.let(entityIds::getValue), text = fact.text,
                localId = fact.factId, kind = "hard_fact", bibleRevisionId = bibleRevisionId,
                createdAt = committedAt,
            )
        }
        return StoryBiblePlanningCommitDraft(
            schemaId = StoryBibleOutputContractV1.schemaId,
            revision = StoryBibleRevisionEntity(
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
            ),
            characters = characters,
            hardFacts = worldRules + hardFacts,
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
        listOf(bookId, outlineRevisionId, outlineStageId).forEach(::requireIdentifier)
        require(InitialPlanningBundleValidator.validateOutline(bible, outline, expectedTargetChapterCount).isEmpty())
        val revision = OutlineRevisionEntity(
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
        return MasterOutlinePlanningCommitDraft(
            schemaId = MasterOutlineOutputContractV1.schemaId,
            revision = revision,
            nodes = listOf(OutlineNodeEntity(
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
            )),
            committedAt = committedAt,
        )
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
        factPayloadJson = JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "kind" to JsonPrimitive(kind),
            "localFactId" to JsonPrimitive(localId),
            "entityId" to (entityId?.let(::JsonPrimitive) ?: JsonNull),
        )).toString(),
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
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun requireIdentifier(value: String) {
        require(value.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
    }
}
