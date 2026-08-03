package app.zhijuan.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentPresentationTest {
    @Test
    fun `three presets map to exact versioned directives`() {
        val reserved = ContentPresentationMappingV1.directiveFor(BookPresentationPreset.RESERVED)
        val balanced = ContentPresentationMappingV1.directiveFor(BookPresentationPreset.BALANCED)
        val detailed = ContentPresentationMappingV1.directiveFor(BookPresentationPreset.DETAILED)

        assertDirective(reserved, narrative = 2, intimacy = 1, fade = FadePolicy.PREFER)
        assertDirective(balanced, narrative = 3, intimacy = 2, fade = FadePolicy.ALLOW)
        assertDirective(detailed, narrative = 4, intimacy = 4, fade = FadePolicy.AVOID)
    }

    @Test
    fun `all presets inherit genre dimensions instead of increasing violence`() {
        val baseline = GenreContentDimensionBaseline(
            conflictDetailLevel = 3,
            graphicInjuryLevel = 1,
            languageIntensityLevel = 2,
            emotionalPressureLevel = 4,
        )

        BookPresentationPreset.entries.forEach { preset ->
            val profile = ContentPresentationMappingV1.resolve(
                directive = ContentPresentationMappingV1.directiveFor(preset),
                genreBaseline = baseline,
            )

            assertEquals(baseline.conflictDetailLevel, profile.conflictDetailLevel)
            assertEquals(baseline.graphicInjuryLevel, profile.graphicInjuryLevel)
            assertEquals(baseline.languageIntensityLevel, profile.languageIntensityLevel)
            assertEquals(baseline.emotionalPressureLevel, profile.emotionalPressureLevel)
        }
    }

    @Test
    fun `detailed and confirmed adults require strict continuity without fade substitution`() {
        val profile = ContentPresentationMappingV1.resolve(
            directive = ContentPresentationMappingV1.directiveFor(BookPresentationPreset.DETAILED),
            genreBaseline = GenreContentDimensionBaseline(2, 1, 1, 2),
        )

        val decision = assertInstanceOf(
            RelevantSceneExecutionDecision.Allowed::class.java,
            ContentPresentationMappingV1.resolveRelevantScene(
                profile = profile,
                adultGate = RelevantCharacterAdultGate.CONFIRMED_ADULTS,
            ),
        )

        assertTrue(decision.strictBodyAndSensoryContinuity)
        assertEquals(100, decision.requiredKeyProcessCoveragePercent)
        assertFalse(decision.fadeSubstitutionAllowed)
        assertTrue(decision.requiresStateContinuity)
        assertTrue(decision.requiresRelevantAftermath)
    }

    @Test
    fun `balanced confirmed scene remains non strict and may fade`() {
        val profile = ContentPresentationMappingV1.resolve(
            directive = ContentPresentationMappingV1.directiveFor(BookPresentationPreset.BALANCED),
            genreBaseline = GenreContentDimensionBaseline(2, 1, 1, 2),
        )

        val decision = assertInstanceOf(
            RelevantSceneExecutionDecision.Allowed::class.java,
            ContentPresentationMappingV1.resolveRelevantScene(
                profile = profile,
                adultGate = RelevantCharacterAdultGate.CONFIRMED_ADULTS,
            ),
        )

        assertFalse(decision.strictBodyAndSensoryContinuity)
        assertNull(decision.requiredKeyProcessCoveragePercent)
        assertTrue(decision.fadeSubstitutionAllowed)
    }

    @Test
    fun `unknown or unconfirmed adults block instead of silently lowering detail`() {
        val profile = ContentPresentationMappingV1.resolve(
            directive = ContentPresentationMappingV1.directiveFor(BookPresentationPreset.DETAILED),
            genreBaseline = GenreContentDimensionBaseline(2, 1, 1, 2),
        )

        assertEquals(
            RelevantSceneExecutionDecision.Blocked(
                RelevantSceneBlockReason.ADULT_STATUS_UNKNOWN,
            ),
            ContentPresentationMappingV1.resolveRelevantScene(
                profile,
                RelevantCharacterAdultGate.UNKNOWN,
            ),
        )
        assertEquals(
            RelevantSceneExecutionDecision.Blocked(
                RelevantSceneBlockReason.ADULT_STATUS_NOT_CONFIRMED,
            ),
            ContentPresentationMappingV1.resolveRelevantScene(
                profile,
                RelevantCharacterAdultGate.NOT_CONFIRMED,
            ),
        )
    }

    @Test
    fun `unsupported schema and out of range values fail closed`() {
        val directive = ContentPresentationMappingV1.directiveFor(BookPresentationPreset.BALANCED)

        assertThrows(IllegalArgumentException::class.java) {
            ContentPresentationMappingV1.resolve(
                directive.copy(presentationMappingSchemaVersion = 2),
                GenreContentDimensionBaseline(2, 1, 1, 2),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenreContentDimensionBaseline(5, 1, 1, 2)
        }
    }

    private fun assertDirective(
        directive: ContentPresentationDirective,
        narrative: Int,
        intimacy: Int,
        fade: FadePolicy,
    ) {
        assertEquals(narrative, directive.narrativeDetailLevel)
        assertEquals(intimacy, directive.intimacyDetailLevel)
        assertEquals(fade, directive.fadePolicy)
        assertNull(directive.conflictDetailOverride)
        assertNull(directive.graphicInjuryOverride)
        assertNull(directive.languageIntensityOverride)
        assertNull(directive.emotionalPressureOverride)
        assertEquals(
            ContentPresentationMappingV1.PRESENTATION_MAPPING_SCHEMA_VERSION,
            directive.presentationMappingSchemaVersion,
        )
        assertEquals(
            ContentPresentationMappingV1.CONTENT_CONTROL_SCHEMA_VERSION,
            directive.contentControlSchemaVersion,
        )
    }
}
