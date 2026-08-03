package app.zhijuan.feature.generation

import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.task.FirstChapterProgressionPolicyV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirstChapterBootstrapStructuredOutputTest {
    @Test
    fun validBootstrapPreservesThreeChapterLookaheadAndAdultFacts() {
        val seed = seed()
        val parsed = assertInstanceOf(
            PlanningOutputValidationResult.Valid::class.java,
            FirstChapterBootstrapOutputParser().parse(payload(seed.contentHash).toByteArray()),
        ).value as FirstChapterBootstrapV1
        val result = FirstChapterBootstrapValidator.validate(parsed, seed)

        assertInstanceOf(FirstChapterBootstrapValidationResult.Valid::class.java, result)
        assertEquals(listOf(1, 2, 3), parsed.roughChapters.map(FirstChapterRoughPlanV1::chapterIndex))
        assertEquals(AdultStatus.CONFIRMED_ADULT, parsed.characters.single().adultStatus)
    }

    @Test
    fun unknownFieldAndNonConsecutiveLookaheadAreRejected() {
        val seed = seed()
        val unknown = payload(seed.contentHash).replace(
            "\"schemaVersion\":1,",
            "\"schemaVersion\":1,\"unexpected\":true,",
        )
        assertInstanceOf(PlanningOutputValidationResult.Invalid::class.java,
            FirstChapterBootstrapOutputParser().parse(unknown.toByteArray()),
        )
        val wrongSequence = payload(seed.contentHash).replace(
            "\"chapterIndex\":2,",
            "\"chapterIndex\":3,",
        )
        assertInstanceOf(PlanningOutputValidationResult.Invalid::class.java,
            FirstChapterBootstrapOutputParser().parse(wrongSequence.toByteArray()),
        )
    }

    @Test
    fun alteredAgeOrSeedHashCannotCrossValidate() {
        val seed = seed()
        val alteredAge = assertInstanceOf(PlanningOutputValidationResult.Valid::class.java,
            FirstChapterBootstrapOutputParser().parse(
                payload(seed.contentHash).replace("\"ageYears\":24", "\"ageYears\":25").toByteArray(),
            ),
        ).value as FirstChapterBootstrapV1
        val badHash = assertInstanceOf(PlanningOutputValidationResult.Valid::class.java,
            FirstChapterBootstrapOutputParser().parse(payload("f".repeat(64)).toByteArray()),
        ).value as FirstChapterBootstrapV1

        assertTrue(
            assertInstanceOf(FirstChapterBootstrapValidationResult.Invalid::class.java,
                FirstChapterBootstrapValidator.validate(alteredAge, seed),
            ).issues.any { it.code == FirstChapterBootstrapCrossIssueCode.CHARACTER_FACT_MISMATCH },
        )
        assertTrue(
            assertInstanceOf(FirstChapterBootstrapValidationResult.Invalid::class.java,
                FirstChapterBootstrapValidator.validate(badHash, seed),
            ).issues.any { it.code == FirstChapterBootstrapCrossIssueCode.SEED_HASH_MISMATCH },
        )
    }

    private fun seed(): StorySeedV1 {
        val canonical = "seed"
        return StorySeedV1(
            targetChapterCount = 80,
            premise = "premise",
            centralConflict = "conflict",
            storyPromise = "promise",
            endingDirection = "ending",
            characters = listOf(
                StorySeedCharacterV1(
                    entityId = "person-a",
                    name = "A",
                    ageYears = 24,
                    adultStatus = AdultStatus.CONFIRMED_ADULT,
                    realIdentifiablePerson = false,
                    intimacyRole = true,
                    storyRole = "lead",
                    desire = "goal",
                    obstacle = "obstacle",
                ),
            ),
            openQuestions = emptyList(),
            canonicalJson = canonical,
            contentHash = sha256(canonical),
        )
    }

    private fun payload(seedHash: String) = """
        {"schemaVersion":1,"contractVersion":"${FirstChapterProgressionPolicyV1.FAST_LANE_CONTRACT_VERSION}","seedContentHash":"$seedHash","characters":[{"entityId":"person-a","ageYears":24,"adultStatus":"CONFIRMED_ADULT","realIdentifiablePerson":false,"intimacyRole":true}],"coreWorldRules":["rule"],"endingDirection":"ending","roughChapters":[{"chapterIndex":1,"goal":"g1","conflict":"c1","turn":"t1","outcome":"o1","hook":"h1"},{"chapterIndex":2,"goal":"g2","conflict":"c2","turn":"t2","outcome":"o2","hook":"h2"},{"chapterIndex":3,"goal":"g3","conflict":"c3","turn":"t3","outcome":"o3","hook":"h3"}],"chapterOnePlan":{"pointOfViewEntityId":"person-a","openingState":"open","sceneSequence":["scene"],"closingState":"close","finalHook":"hook"}}
    """.trimIndent()

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
