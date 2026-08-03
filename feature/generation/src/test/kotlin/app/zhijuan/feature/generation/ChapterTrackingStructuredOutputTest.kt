package app.zhijuan.feature.generation

import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.StoryEntityType
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterTrackingStructuredOutputTest {
    @Test
    fun validOutputPassesBoundContractAndMapsAppendOnlyTransitions() {
        val bytes = validJson().toByteArray(StandardCharsets.UTF_8)
        val result = StructuredOutputValidator().validate(bytes, BoundChapterTrackingOutputContract(expectation()))
        assertTrue(result is StructuredOutputValidationResult.Valid)
        val parsed = ChapterTrackingOutputParser().parse(bytes)
        assertTrue(parsed is PlanningOutputValidationResult.Valid)
        val tracking = (parsed as PlanningOutputValidationResult.Valid).value

        val mapped = ChapterTrackingProjectionPersistenceMapper.map(
            tracking,
            ChapterTrackingProjectionMappingSpec(
                bookId = "book.one",
                generationStageId = "stage.tracking.one",
                modelSnapshotJson = "{\"model\":\"fixture\"}",
                createdAt = 90L,
            ),
        )

        assertEquals(1, mapped.timelineEvents.size)
        assertEquals(1, mapped.newForeshadows.size)
        assertEquals(1, mapped.existingForeshadowUpdates.size)
        assertEquals(2, mapped.foreshadowTransitions.size)
        assertEquals(ForeshadowStatus.DEVELOPING, mapped.existingForeshadowUpdates.single().toStatus)
        assertEquals(ForeshadowStatus.PLANTED, mapped.newForeshadows.single().foreshadowStatus)
        assertEquals(mapped.projection.payloadHash.length, 64)
        assertEquals(tracking.contentHash, mapped.projection.outputContentHash)
    }

    @Test
    fun everyFrozenSourceHashMustMatch() {
        val changed = validJson().replace("\"memorySnapshotHash\":\"$MEMORY_HASH\"", "\"memorySnapshotHash\":\"${"d".repeat(64)}\"")
        val result = StructuredOutputValidator().validate(
            changed.toByteArray(StandardCharsets.UTF_8),
            BoundChapterTrackingOutputContract(expectation()),
        )

        assertTrue(result is StructuredOutputValidationResult.Invalid)
        result as StructuredOutputValidationResult.Invalid
        assertTrue(result.report.issues.any { it.path == "$.memorySnapshotHash" })
    }

    @Test
    fun unknownParticipantAndNonLocationLocationAreRejected() {
        val changed = validJson()
            .replace("[\"char.hero\"]", "[\"char.unknown\"]", ignoreCase = false)
            .replace("\"locationEntityId\":\"loc.hall\"", "\"locationEntityId\":\"char.hero\"")
        val result = StructuredOutputValidator().validate(
            changed.toByteArray(StandardCharsets.UTF_8),
            BoundChapterTrackingOutputContract(expectation()),
        )

        assertTrue(result is StructuredOutputValidationResult.Invalid)
        result as StructuredOutputValidationResult.Invalid
        assertTrue(result.report.issues.any { it.path.endsWith("participantEntityIds") })
        assertTrue(result.report.issues.any { it.path.endsWith("locationEntityId") })
    }

    @Test
    fun existingForeshadowMustEchoIdentityStateDescriptionAndImportance() {
        val changed = validJson()
            .replace("\"fromStatus\":\"PLANTED\"", "\"fromStatus\":\"PLANNED\"")
            .replace("\"importance\":80", "\"importance\":79")
        val result = StructuredOutputValidator().validate(
            changed.toByteArray(StandardCharsets.UTF_8),
            BoundChapterTrackingOutputContract(expectation()),
        )

        assertTrue(result is StructuredOutputValidationResult.Invalid)
        result as StructuredOutputValidationResult.Invalid
        assertTrue(result.report.issues.any { it.path.endsWith("fromStatus") })
        assertTrue(result.report.issues.any { it.path.endsWith("importance") })
    }

    @Test
    fun abandoningAClueRequiresExplicitMaximumConfidence() {
        val changed = validJson()
            .replace("\"operation\":\"DEVELOP\"", "\"operation\":\"ABANDON\"")
            .replace("\"confidenceMicros\":900000", "\"confidenceMicros\":999999")
        val result = StructuredOutputValidator().validate(
            changed.toByteArray(StandardCharsets.UTF_8),
            BoundChapterTrackingOutputContract(expectation()),
        )

        assertTrue(result is StructuredOutputValidationResult.Invalid)
        result as StructuredOutputValidationResult.Invalid
        assertTrue(result.report.issues.any { it.path.endsWith("operation") })
    }

    @Test
    fun duplicateTransitionTargetCannotAdvanceTwiceInOneChapter() {
        val duplicate = validJson().replace(
            "\"foreshadowOperations\":[",
            "\"foreshadowOperations\":[{\"operation\":\"RESOLVE\",\"foreshadowItemId\":\"clue.old\",\"description\":\"门后反复出现的银铃声\",\"targetStartChapterIndex\":null,\"targetEndChapterIndex\":null,\"visibleEntityIds\":[\"char.hero\"],\"importance\":80,\"fromStatus\":\"PLANTED\",\"confidenceMicros\":950000,\"evidence\":\"银铃来源被揭示\"},",
        )
        val result = StructuredOutputValidator().validate(
            duplicate.toByteArray(StandardCharsets.UTF_8),
            BoundChapterTrackingOutputContract(expectation()),
        )

        assertTrue(result is StructuredOutputValidationResult.Invalid)
        result as StructuredOutputValidationResult.Invalid
        assertTrue(result.report.issues.any { it.path.endsWith("foreshadowItemId") })
    }

    @Test
    fun defaultStringsDoNotExposeTrackingContent() {
        val parsed = ChapterTrackingOutputParser().parse(validJson().toByteArray(StandardCharsets.UTF_8))
        val tracking = (parsed as PlanningOutputValidationResult.Valid).value
        assertFalse(tracking.toString().contains("银铃"))
        assertFalse(tracking.toString().contains("封蜡"))
    }

    private fun expectation() = ChapterTrackingExpectation(
        sourceChapterVersionId = "version.one",
        sourceChapterContentHash = CONTENT_HASH,
        chapterId = "chapter.one",
        chapterIndex = 2,
        memorySnapshotHash = MEMORY_HASH,
        priorForeshadowSnapshotHash = FORESHADOW_HASH,
        knownEntitySnapshotHash = ENTITY_HASH,
        knownEntities = linkedMapOf(
            "char.hero" to StoryEntityType.CHARACTER,
            "loc.hall" to StoryEntityType.LOCATION,
        ),
        priorForeshadows = mapOf(
            "clue.old" to TrackingKnownForeshadow(
                foreshadowItemId = "clue.old",
                description = "门后反复出现的银铃声",
                status = ForeshadowStatus.PLANTED,
                visibleEntityIds = setOf("char.hero"),
                importance = 80,
            ),
        ),
    )

    private fun validJson(): String =
        """
        {
          "schemaVersion":1,
          "sourceChapterVersionId":"version.one",
          "sourceChapterContentHash":"$CONTENT_HASH",
          "chapterId":"chapter.one",
          "chapterIndex":2,
          "memorySnapshotHash":"$MEMORY_HASH",
          "priorForeshadowSnapshotHash":"$FORESHADOW_HASH",
          "knownEntitySnapshotHash":"$ENTITY_HASH",
          "timelineEvents":[{
            "name":"主角在旧厅发现第二道门",
            "participantEntityIds":["char.hero"],
            "locationEntityId":"loc.hall",
            "storyTimeExpression":"同夜子时前",
            "constraints":["发现发生在银铃再次响起之后"],
            "evidence":"主角听见银铃后推开夹层门"
          }],
          "foreshadowOperations":[{
            "operation":"DEVELOP",
            "foreshadowItemId":"clue.old",
            "description":"门后反复出现的银铃声",
            "targetStartChapterIndex":null,
            "targetEndChapterIndex":null,
            "visibleEntityIds":["char.hero"],
            "importance":80,
            "fromStatus":"PLANTED",
            "confidenceMicros":900000,
            "evidence":"银铃与夹层门同步出现"
          },{
            "operation":"PLANT",
            "foreshadowItemId":null,
            "description":"破损信封上的双重封蜡",
            "targetStartChapterIndex":3,
            "targetEndChapterIndex":8,
            "visibleEntityIds":["char.hero"],
            "importance":70,
            "fromStatus":null,
            "confidenceMicros":920000,
            "evidence":"信封特写明确写出两层不同印记"
          }]
        }
        """.trimIndent()

    private companion object {
        val CONTENT_HASH = "a".repeat(64)
        val MEMORY_HASH = "b".repeat(64)
        val FORESHADOW_HASH = "c".repeat(64)
        val ENTITY_HASH = "e".repeat(64)
    }
}
