package app.zhijuan.feature.generation

import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterMemoryStructuredOutputTest {
    @Test
    fun validExtractionPreservesEndingContinuityAndKnownEntityProvenance() {
        val memory = validMemory()
        val result = ChapterMemoryValidatorV1.validate(memory, expectation())

        assertTrue(result is ChapterMemoryValidationResult.Valid)
        assertTrue(memory.summary.endingState.contains("手腕仍有擦伤"))
        assertEquals(ChapterMemoryAttributeV1.PHYSICAL_STATE, memory.entityEvents.first().attribute)
        assertEquals(CanonLevel.STORY_CANON, memory.facts.single().canonLevel)
        assertFalse(memory.toString().contains("手腕仍有擦伤"))
    }

    @Test
    fun strictContractRejectsUnknownFieldsDuplicateKeysAndForbiddenCanonLevels() {
        val parser = ChapterMemoryOutputParser()
        val unknown = validJson().replace(
            "\"facts\":[",
            "\"extra\":true,\"facts\":[",
        )
        val duplicate = validJson().replace(
            "\"chapterIndex\":1",
            "\"chapterIndex\":1,\"chapterIndex\":1",
        )
        val hardCanon = validJson().replace("\"STORY_CANON\"", "\"HARD_CANON\"")

        assertIssue(parser.parse(unknown.encodeToByteArray()), StructuredOutputIssueCode.UNKNOWN_FIELD)
        assertIssue(parser.parse(duplicate.encodeToByteArray()), StructuredOutputIssueCode.DUPLICATE_KEY)
        assertIssue(parser.parse(hardCanon.encodeToByteArray()), StructuredOutputIssueCode.VALUE_INVALID)
    }

    @Test
    fun sourceOrEntityMismatchFailsWithoutGuessing() {
        val memory = validMemory().copy(
            sourceChapterContentHash = "b".repeat(64),
            entityEvents = validMemory().entityEvents.map { it.copy(entityId = "char.unknown") },
        )
        val result = ChapterMemoryValidatorV1.validate(memory, expectation()) as ChapterMemoryValidationResult.Invalid

        assertEquals(
            setOf(
                ChapterMemoryCrossIssueCode.SOURCE_CONTENT_HASH_MISMATCH,
                ChapterMemoryCrossIssueCode.UNKNOWN_ENTITY,
            ),
            result.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun duplicateEventsFactsAndInvalidRelationshipTargetsAreRejected() {
        val base = validMemory()
        val duplicateEvent = base.entityEvents.first()
        val duplicateFact = base.facts.first()
        val memory = base.copy(
            entityEvents = listOf(
                duplicateEvent.copy(attribute = ChapterMemoryAttributeV1.RELATIONSHIP, relatedEntityId = "char.lin"),
                duplicateEvent.copy(attribute = ChapterMemoryAttributeV1.RELATIONSHIP, relatedEntityId = "char.lin"),
            ),
            facts = listOf(duplicateFact, duplicateFact.copy(text = duplicateFact.text.uppercase())),
        )
        val result = ChapterMemoryValidatorV1.validate(memory, expectation()) as ChapterMemoryValidationResult.Invalid

        assertTrue(result.issues.any { it.code == ChapterMemoryCrossIssueCode.INVALID_RELATIONSHIP_TARGET })
        assertTrue(result.issues.any { it.code == ChapterMemoryCrossIssueCode.DUPLICATE_ENTITY_EVENT })
        assertTrue(result.issues.any { it.code == ChapterMemoryCrossIssueCode.DUPLICATE_FACT })
    }

    @Test
    fun persistenceMappingIsDeterministicAndBindsEveryRowToTheSameVersion() {
        val memory = validMemory()
        val spec = ChapterMemoryExtractionMappingSpec(
            bookId = "book.1",
            generationStageId = "stage.extract.1",
            modelSnapshotJson = "{\"model\":\"fixture\"}",
            createdAt = 50L,
        )

        val first = ChapterMemoryExtractionPersistenceMapper.map(memory, spec)
        val second = ChapterMemoryExtractionPersistenceMapper.map(memory, spec)

        assertEquals(first, second)
        assertEquals("chapter.version.1", first.summary.chapterVersionId)
        assertTrue(first.entityEvents.all { it.sourceChapterVersionId == "chapter.version.1" })
        assertTrue(first.canonFacts.all { it.sourceChapterVersionId == "chapter.version.1" })
        assertTrue(first.canonFacts.all { it.sourceBibleRevisionId == null })
        assertTrue(first.entityEvents.all { it.status == DerivedDataStatus.VALID })
        assertFalse(first.toString().contains("擦伤"))
    }

    @Test
    fun requestFactoryBindsContentHashKnownEntitiesAndExactSchemaWithoutLeakingContent() {
        val chapter = "章节正文测试；结尾状态必须被提取。"
        val bound = ChapterMemoryExtractionRequestFactory.create(
            ChapterMemoryExtractionRequestSpec(
                requestId = "request.1",
                generationId = "job.1",
                stageId = "stage.extract.1",
                attemptId = "attempt.1",
                modelId = ProviderModelId.from("fixture-model"),
                sourceChapterVersionId = "chapter.version.1",
                sourceChapterContentHash = sha256(chapter),
                chapterId = "chapter.1",
                chapterIndex = 1,
                chapterContent = chapter,
                knownEntities = listOf(
                    ChapterMemoryKnownEntity(
                        entityId = "char.lin",
                        canonicalName = "林澈",
                        entityType = StoryEntityType.CHARACTER,
                        adultStatus = AdultStatus.CONFIRMED_ADULT,
                    ),
                ),
                maximumOutputTokens = 2_048,
                timeouts = ProviderTimeoutPolicy(1_000, 2_000, 2_000, 10_000),
            ),
        )

        assertTrue(bound.request.stream)
        val expected = ChapterMemoryOutputContractV1.providerSchema.withValue { it }
        assertTrue(bound.request.structuredOutputSchema?.withValue { it == expected } == true)
        assertEquals(setOf("char.lin"), bound.expectation.allowedEntityIds)
        assertEquals(64, bound.sourceBindingHash.length)
        assertFalse(bound.toString().contains(chapter))
        assertFalse(bound.request.toString().contains(chapter))
    }

    private fun validMemory(): ChapterMemoryV1 {
        val parsed = ChapterMemoryOutputParser().parse(validJson().encodeToByteArray())
        return (parsed as PlanningOutputValidationResult.Valid).value
    }

    private fun validJson(): String = """
        {"schemaVersion":1,"sourceChapterVersionId":"chapter.version.1","sourceChapterContentHash":"${"a".repeat(64)}","chapterId":"chapter.1","chapterIndex":1,"summary":{"objectiveOutcome":"主角取得线索但暴露行踪","keyEvents":["取得封存记录"],"decisions":["决定继续调查"],"relationshipChanges":[],"endingState":"主角回到住处，手腕仍有擦伤，保持警惕。","unresolvedQuestions":["谁修改了记录"],"importance":80},"entityEvents":[{"entityId":"char.lin","attribute":"PHYSICAL_STATE","relatedEntityId":null,"oldValue":null,"newValue":"手腕擦伤且尚未处理","storyTimeExpression":"当晚","confidenceMicros":980000,"canonLevel":"STORY_CANON","evidence":"章节结尾明确保留该状态"}],"facts":[{"factKind":"DISCOVERY","entityId":"char.lin","text":"林澈发现记录被人为修改","canonLevel":"STORY_CANON","confidenceMicros":990000,"conflictGroupId":null}]}
    """.trimIndent()

    private fun expectation() = ChapterMemoryExtractionExpectation(
        sourceChapterVersionId = "chapter.version.1",
        sourceChapterContentHash = "a".repeat(64),
        chapterId = "chapter.1",
        chapterIndex = 1,
        allowedEntityIds = setOf("char.lin", "char.yao"),
    )

    private fun assertIssue(
        result: PlanningOutputValidationResult<ChapterMemoryV1>,
        code: StructuredOutputIssueCode,
    ) {
        val invalid = result as PlanningOutputValidationResult.Invalid
        assertTrue(invalid.report.issues.any { it.code == code })
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
