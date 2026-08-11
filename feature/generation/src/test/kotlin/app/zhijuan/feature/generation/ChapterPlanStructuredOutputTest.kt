package app.zhijuan.feature.generation

import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.RelevantSceneBlockReason
import app.zhijuan.core.task.PromptInstruction
import app.zhijuan.core.task.SceneExecutionContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterPlanStructuredOutputTest {
    private val parser = ChapterPlanOutputParser()

    @Test
    fun `schema is provider-ready and output remains bounded to forty-eight kibibytes`() {
        assertEquals("chapter-plan.v1", ChapterPlanOutputContractV1.schemaId)
        assertEquals(48 * 1_024, ChapterPlanOutputContractV1.limits.maximumBytes)
        assertEquals(3, ChapterPlanOutputContractV1.MINIMUM_STRICT_PROCESS_NODES_PER_SCENE)
        assertEquals(64, ChapterPlanOutputContractV1.MAXIMUM_TOTAL_PROCESS_NODES)
        ChapterPlanOutputContractV1.providerSchema.withValue { schema ->
            assertTrue("\"maxItems\":12" in schema)
            assertTrue("\"requiredProcessNodes\"" in schema)
            assertTrue("\"clothingAndObjectStateAfter\"" in schema)
            assertTrue("\"aftermath\"" in schema)
            assertTrue("\"additionalProperties\":false" in schema)
        }
    }

    @Test
    fun `ordinary non-intimacy plan parses validates and redacts content`() {
        val plan = parse(validJson())
        val result = ChapterPlanBusinessValidatorV1.validate(plan, notApplicableExpectation())

        assertInstanceOf(ChapterPlanValidationResult.Valid::class.java, result)
        assertEquals(1, plan.scenes.size)
        assertTrue(plan.requiredProcessNodeIds.isEmpty())
        assertTrue(plan.toString().contains("content=redacted"))
        assertFalse(plan.toString().contains("Advance the investigation"))
        assertFalse(plan.scenes.single().toString().contains("Evidence creates resistance"))
    }

    @Test
    fun `canonical hash is stable when root member order changes`() {
        val original = parse(validJson())
        val document = Json.parseToJsonElement(validJson().decodeToString()) as JsonObject
        val reversed = JsonObject(document.entries.reversed().associate { (key, value) -> key to value })
        val reordered = parse(reversed.toString().encodeToByteArray())

        assertEquals(original.canonicalJson, reordered.canonicalJson)
        assertEquals(original.contentHash, reordered.contentHash)
    }

    @Test
    fun `strict adult fictional scene freezes ordered body sensory and aftermath evidence`() {
        val plan = parse(
            validJson(
                intimacyRelevant = true,
                participants = listOf("char.a", "char.b"),
                processNodeCount = 3,
                aftermath = "The scene changes their physical state, relationship, and next decision.",
            ),
        )
        val result = ChapterPlanBusinessValidatorV1.validate(plan, strictExpectation())

        assertInstanceOf(ChapterPlanValidationResult.Valid::class.java, result)
        assertEquals(listOf("process.1", "process.2", "process.3"), plan.requiredProcessNodeIds)
        assertTrue(plan.scenes.single().requiredProcessNodes.all { it.sensoryChange.isNotBlank() })
        assertTrue(plan.scenes.single().requiredProcessNodes.all { it.bodyStateAfter.isNotBlank() })
        assertTrue(plan.scenes.single().aftermath!!.isNotBlank())
    }

    @Test
    fun `strict scene rejects unconfirmed participant too few process nodes and missing aftermath`() {
        val plan = parse(
            validJson(
                intimacyRelevant = true,
                participants = listOf("char.a", "char.unconfirmed"),
                processNodeCount = 2,
                aftermath = null,
            ),
        )
        val expectation = strictExpectation(
            known = setOf("char.a", "char.unconfirmed"),
            confirmed = setOf("char.a"),
        )
        val result = ChapterPlanBusinessValidatorV1.validate(plan, expectation)
            as ChapterPlanValidationResult.Invalid

        assertTrue(result.issues.any { it.code == ChapterPlanCrossIssueCode.ADULT_FICTIONAL_GATE_MISMATCH })
        assertTrue(result.issues.any { it.code == ChapterPlanCrossIssueCode.REQUIRED_PROCESS_NODES_MISSING })
        assertTrue(result.issues.any { it.code == ChapterPlanCrossIssueCode.AFTERMATH_MISSING })
        assertTrue(result.toString().contains("content=redacted"))
    }

    @Test
    fun `allowed chapter cannot evade relevance and proportional scenes cannot forge strict nodes`() {
        val missingRelevant = parse(validJson())
        val missingResult = ChapterPlanBusinessValidatorV1.validate(missingRelevant, strictExpectation())
            as ChapterPlanValidationResult.Invalid
        assertTrue(
            missingResult.issues.any { it.code == ChapterPlanCrossIssueCode.REQUIRED_INTIMACY_SCENE_MISSING },
        )

        val forgedStrictNodes = parse(
            validJson(
                intimacyRelevant = true,
                participants = listOf("char.a", "char.b"),
                processNodeCount = 3,
                aftermath = "The relationship and next decision change.",
            ),
        )
        val proportionalResult = ChapterPlanBusinessValidatorV1.validate(
            forgedStrictNodes,
            proportionalExpectation(),
        ) as ChapterPlanValidationResult.Invalid
        assertTrue(
            proportionalResult.issues.any { it.code == ChapterPlanCrossIssueCode.PROCESS_NODES_FORBIDDEN },
        )
    }

    @Test
    fun `identity unknown character and pov membership drift all fail closed`() {
        val plan = parse(
            validJson(
                chapterId = "chapter.other",
                participants = listOf("char.a", "char.unknown"),
                pointOfViewCharacterId = "char.b",
            ),
        )
        val result = ChapterPlanBusinessValidatorV1.validate(plan, notApplicableExpectation())
            as ChapterPlanValidationResult.Invalid

        assertTrue(result.issues.any { it.code == ChapterPlanCrossIssueCode.CHAPTER_ID_MISMATCH })
        assertTrue(result.issues.any { it.code == ChapterPlanCrossIssueCode.UNKNOWN_CHARACTER_REFERENCE })
        assertTrue(result.issues.any { it.code == ChapterPlanCrossIssueCode.POV_NOT_PARTICIPANT })
    }

    @Test
    fun `duplicate keys unknown fields malformed sequences and oversized output are rejected structurally`() {
        val duplicateKey = validJson().decodeToString().replace(
            "\"schemaVersion\":1,",
            "\"schemaVersion\":1,\"schemaVersion\":1,",
        ).encodeToByteArray()
        assertInstanceOf(PlanningOutputValidationResult.Invalid::class.java, parser.parse(duplicateKey))

        val unknownField = validJson().decodeToString().replace(
            "\"chapterIndex\":1,",
            "\"chapterIndex\":1,\"unexpected\":true,",
        ).encodeToByteArray()
        assertInstanceOf(PlanningOutputValidationResult.Invalid::class.java, parser.parse(unknownField))

        val malformedSequence = validJson(
            intimacyRelevant = true,
            participants = listOf("char.a", "char.b"),
            processNodeCount = 3,
            processSequenceOffset = 1,
            aftermath = "The relationship and next decision change.",
        )
        assertInstanceOf(PlanningOutputValidationResult.Invalid::class.java, parser.parse(malformedSequence))

        val oversized = ByteArray(ChapterPlanOutputContractV1.MAXIMUM_OUTPUT_BYTES + 1) { ' '.code.toByte() }
        val oversizedResult = parser.parse(oversized) as PlanningOutputValidationResult.Invalid
        assertEquals(StructuredOutputIssueCode.BYTE_LIMIT_EXCEEDED, oversizedResult.report.issues.single().code)
        assertFalse(oversizedResult.report.repairEligible)
    }

    @Test
    fun `blocked adult gate cannot be converted into a plan expectation`() {
        assertThrows(IllegalStateException::class.java) {
            ChapterPlanExpectationV1(
                chapterId = "chapter.1",
                chapterIndex = 1,
                contextContentHash = "a".repeat(64),
                contextSourceManifestHash = "b".repeat(64),
                knownCharacterIds = setOf("char.a"),
                confirmedAdultFictionalCharacterIds = emptySet(),
                sceneExecutionContract = SceneExecutionContract.Blocked(
                    RelevantSceneBlockReason.ADULT_STATUS_UNKNOWN,
                ),
            )
        }
    }

    private fun parse(raw: ByteArray): ChapterPlanV1 = when (val result = parser.parse(raw)) {
        is PlanningOutputValidationResult.Valid -> result.value
        is PlanningOutputValidationResult.Invalid -> error(result.report.toString())
    }

    private fun notApplicableExpectation() = ChapterPlanExpectationV1(
        chapterId = "chapter.1",
        chapterIndex = 1,
        contextContentHash = "a".repeat(64),
        contextSourceManifestHash = "b".repeat(64),
        knownCharacterIds = setOf("char.a", "char.b"),
        confirmedAdultFictionalCharacterIds = emptySet(),
        sceneExecutionContract = SceneExecutionContract.NotApplicable,
    )

    private fun strictExpectation(
        known: Set<String> = setOf("char.a", "char.b"),
        confirmed: Set<String> = known,
    ) = ChapterPlanExpectationV1(
        chapterId = "chapter.1",
        chapterIndex = 1,
        contextContentHash = "a".repeat(64),
        contextSourceManifestHash = "b".repeat(64),
        knownCharacterIds = known,
        confirmedAdultFictionalCharacterIds = confirmed,
        sceneExecutionContract = SceneExecutionContract.Allowed(
            automatic = true,
            intimacyDetailLevel = 4,
            fadePolicy = FadePolicy.AVOID,
            strictBodyAndSensoryContinuity = true,
            requiredKeyProcessCoveragePercent = 100,
            fadeSubstitutionAllowed = false,
            requiresStateContinuity = true,
            requiresRelevantAftermath = true,
            instructions = listOf(PromptInstruction("scene.strict", "Preserve the complete scene contract.")),
        ),
    )

    private fun proportionalExpectation() = ChapterPlanExpectationV1(
        chapterId = "chapter.1",
        chapterIndex = 1,
        contextContentHash = "a".repeat(64),
        contextSourceManifestHash = "b".repeat(64),
        knownCharacterIds = setOf("char.a", "char.b"),
        confirmedAdultFictionalCharacterIds = setOf("char.a", "char.b"),
        sceneExecutionContract = SceneExecutionContract.Allowed(
            automatic = true,
            intimacyDetailLevel = 2,
            fadePolicy = FadePolicy.ALLOW,
            strictBodyAndSensoryContinuity = false,
            requiredKeyProcessCoveragePercent = null,
            fadeSubstitutionAllowed = true,
            requiresStateContinuity = true,
            requiresRelevantAftermath = true,
            instructions = listOf(PromptInstruction("scene.proportional", "Preserve proportional continuity.")),
        ),
    )

    private fun validJson(
        chapterId: String = "chapter.1",
        chapterIndex: Int = 1,
        participants: List<String> = listOf("char.a"),
        pointOfViewCharacterId: String = participants.first(),
        intimacyRelevant: Boolean = false,
        processNodeCount: Int = 0,
        processSequenceOffset: Int = 0,
        aftermath: String? = null,
    ): ByteArray {
        val participantJson = participants.joinToString(",") { "\"$it\"" }
        val nodes = (1..processNodeCount).joinToString(",") { index ->
            val sequence = index + processSequenceOffset
            """{"nodeId":"process.$index","sequence":$sequence,"action":"A concrete action advances the planned change.","reaction":"The other participant gives an observable response.","spatialStateAfter":"Positions and contact points are explicit after the change.","bodyStateAfter":"Relevant physical state and exertion carry forward.","clothingAndObjectStateAfter":"Clothing and nearby objects retain their resulting state.","sensoryChange":"Only viewpoint-accessible sensory changes are recorded."}"""
        }
        val aftermathJson = aftermath?.let { "\"$it\"" } ?: "null"
        return """
            {"schemaVersion":1,"policyVersion":"zhijuan.chapter-plan-output-policy.v1","chapterId":"$chapterId","chapterIndex":$chapterIndex,"contextContentHash":"${"a".repeat(64)}","contextSourceManifestHash":"${"b".repeat(64)}","openingState":"The chapter begins from the frozen prior state.","chapterGoal":"Advance the investigation and force a consequential choice.","closingState":"The choice changes what the characters can do next.","finalHook":"A contradiction makes the next chapter unavoidable.","continuityConstraints":["Known locations, injuries, possessions, and promises cannot reset without cause."],"scenes":[{"sceneId":"scene.1","sequence":1,"purpose":"Advance the investigation while changing the relationship.","location":"The established archive room.","pointOfViewCharacterId":"$pointOfViewCharacterId","participantCharacterIds":[$participantJson],"openingState":"Participants enter with the frozen physical and emotional state.","turn":"Evidence creates resistance and forces an irreversible response.","closingState":"The resulting position, body state, possessions, and relationship persist.","continuityCarry":["Carry the resulting position, body state, possessions, and relationship into the next scene."],"intimacyRelevant":$intimacyRelevant,"requiredProcessNodes":[$nodes],"aftermath":$aftermathJson}]}
        """.trimIndent().encodeToByteArray()
    }
}
