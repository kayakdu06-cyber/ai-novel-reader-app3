package app.zhijuan.core.model

enum class BookPresentationPreset {
    RESERVED,
    BALANCED,
    DETAILED,
}

enum class FadePolicy {
    PREFER,
    ALLOW,
    AVOID,
}

enum class RelevantCharacterAdultGate {
    CONFIRMED_ADULTS,
    NOT_CONFIRMED,
    UNKNOWN,
}

enum class RelevantSceneBlockReason {
    ADULT_STATUS_NOT_CONFIRMED,
    ADULT_STATUS_UNKNOWN,
}

data class ContentPresentationDirective(
    val preset: BookPresentationPreset,
    val narrativeDetailLevel: Int,
    val intimacyDetailLevel: Int,
    val fadePolicy: FadePolicy,
    val conflictDetailOverride: Int? = null,
    val graphicInjuryOverride: Int? = null,
    val languageIntensityOverride: Int? = null,
    val emotionalPressureOverride: Int? = null,
    val presentationMappingSchemaVersion: Int,
    val contentControlSchemaVersion: Int,
) {
    init {
        requireDetailLevel("narrativeDetailLevel", narrativeDetailLevel)
        requireDetailLevel("intimacyDetailLevel", intimacyDetailLevel)
        conflictDetailOverride?.let { requireDetailLevel("conflictDetailOverride", it) }
        graphicInjuryOverride?.let { requireDetailLevel("graphicInjuryOverride", it) }
        languageIntensityOverride?.let { requireDetailLevel("languageIntensityOverride", it) }
        emotionalPressureOverride?.let { requireDetailLevel("emotionalPressureOverride", it) }
    }
}

data class GenreContentDimensionBaseline(
    val conflictDetailLevel: Int,
    val graphicInjuryLevel: Int,
    val languageIntensityLevel: Int,
    val emotionalPressureLevel: Int,
) {
    init {
        requireDetailLevel("conflictDetailLevel", conflictDetailLevel)
        requireDetailLevel("graphicInjuryLevel", graphicInjuryLevel)
        requireDetailLevel("languageIntensityLevel", languageIntensityLevel)
        requireDetailLevel("emotionalPressureLevel", emotionalPressureLevel)
    }
}

data class ContentControlProfile(
    val preset: BookPresentationPreset,
    val narrativeDetailLevel: Int,
    val intimacyDetailLevel: Int,
    val conflictDetailLevel: Int,
    val graphicInjuryLevel: Int,
    val languageIntensityLevel: Int,
    val emotionalPressureLevel: Int,
    val fadePolicy: FadePolicy,
    val presentationMappingSchemaVersion: Int,
    val contentControlSchemaVersion: Int,
) {
    init {
        requireDetailLevel("narrativeDetailLevel", narrativeDetailLevel)
        requireDetailLevel("intimacyDetailLevel", intimacyDetailLevel)
        requireDetailLevel("conflictDetailLevel", conflictDetailLevel)
        requireDetailLevel("graphicInjuryLevel", graphicInjuryLevel)
        requireDetailLevel("languageIntensityLevel", languageIntensityLevel)
        requireDetailLevel("emotionalPressureLevel", emotionalPressureLevel)
    }
}

sealed interface RelevantSceneExecutionDecision {
    data class Allowed(
        val intimacyDetailLevel: Int,
        val fadePolicy: FadePolicy,
        val strictBodyAndSensoryContinuity: Boolean,
        val requiredKeyProcessCoveragePercent: Int?,
        val fadeSubstitutionAllowed: Boolean,
        val requiresStateContinuity: Boolean,
        val requiresRelevantAftermath: Boolean,
    ) : RelevantSceneExecutionDecision {
        init {
            requireDetailLevel("intimacyDetailLevel", intimacyDetailLevel)
            require(requiredKeyProcessCoveragePercent == null ||
                requiredKeyProcessCoveragePercent in 0..100)
            require(!strictBodyAndSensoryContinuity || requiredKeyProcessCoveragePercent == 100)
            require(!strictBodyAndSensoryContinuity || !fadeSubstitutionAllowed)
        }
    }

    data class Blocked(
        val reason: RelevantSceneBlockReason,
    ) : RelevantSceneExecutionDecision
}

object ContentPresentationMappingV1 {
    const val PRESENTATION_MAPPING_SCHEMA_VERSION = 1
    const val CONTENT_CONTROL_SCHEMA_VERSION = 1

    fun directiveFor(preset: BookPresentationPreset): ContentPresentationDirective = when (preset) {
        BookPresentationPreset.RESERVED -> directive(
            preset = preset,
            narrativeDetailLevel = 2,
            intimacyDetailLevel = 1,
            fadePolicy = FadePolicy.PREFER,
        )
        BookPresentationPreset.BALANCED -> directive(
            preset = preset,
            narrativeDetailLevel = 3,
            intimacyDetailLevel = 2,
            fadePolicy = FadePolicy.ALLOW,
        )
        BookPresentationPreset.DETAILED -> directive(
            preset = preset,
            narrativeDetailLevel = 4,
            intimacyDetailLevel = 4,
            fadePolicy = FadePolicy.AVOID,
        )
    }

    fun resolve(
        directive: ContentPresentationDirective,
        genreBaseline: GenreContentDimensionBaseline,
    ): ContentControlProfile {
        require(directive.presentationMappingSchemaVersion == PRESENTATION_MAPPING_SCHEMA_VERSION) {
            "Unsupported presentation mapping schema"
        }
        require(directive.contentControlSchemaVersion == CONTENT_CONTROL_SCHEMA_VERSION) {
            "Unsupported content control schema"
        }
        return ContentControlProfile(
            preset = directive.preset,
            narrativeDetailLevel = directive.narrativeDetailLevel,
            intimacyDetailLevel = directive.intimacyDetailLevel,
            conflictDetailLevel = directive.conflictDetailOverride
                ?: genreBaseline.conflictDetailLevel,
            graphicInjuryLevel = directive.graphicInjuryOverride
                ?: genreBaseline.graphicInjuryLevel,
            languageIntensityLevel = directive.languageIntensityOverride
                ?: genreBaseline.languageIntensityLevel,
            emotionalPressureLevel = directive.emotionalPressureOverride
                ?: genreBaseline.emotionalPressureLevel,
            fadePolicy = directive.fadePolicy,
            presentationMappingSchemaVersion = directive.presentationMappingSchemaVersion,
            contentControlSchemaVersion = directive.contentControlSchemaVersion,
        )
    }

    fun resolveRelevantScene(
        profile: ContentControlProfile,
        adultGate: RelevantCharacterAdultGate,
    ): RelevantSceneExecutionDecision {
        if (adultGate != RelevantCharacterAdultGate.CONFIRMED_ADULTS) {
            return RelevantSceneExecutionDecision.Blocked(
                reason = when (adultGate) {
                    RelevantCharacterAdultGate.NOT_CONFIRMED ->
                        RelevantSceneBlockReason.ADULT_STATUS_NOT_CONFIRMED
                    RelevantCharacterAdultGate.UNKNOWN ->
                        RelevantSceneBlockReason.ADULT_STATUS_UNKNOWN
                    RelevantCharacterAdultGate.CONFIRMED_ADULTS -> error("Already handled")
                },
            )
        }

        val strictContinuity = profile.intimacyDetailLevel == MAX_DETAIL_LEVEL &&
            profile.fadePolicy == FadePolicy.AVOID
        return RelevantSceneExecutionDecision.Allowed(
            intimacyDetailLevel = profile.intimacyDetailLevel,
            fadePolicy = profile.fadePolicy,
            strictBodyAndSensoryContinuity = strictContinuity,
            requiredKeyProcessCoveragePercent = if (strictContinuity) 100 else null,
            fadeSubstitutionAllowed = profile.fadePolicy != FadePolicy.AVOID,
            requiresStateContinuity = true,
            requiresRelevantAftermath = true,
        )
    }

    private fun directive(
        preset: BookPresentationPreset,
        narrativeDetailLevel: Int,
        intimacyDetailLevel: Int,
        fadePolicy: FadePolicy,
    ) = ContentPresentationDirective(
        preset = preset,
        narrativeDetailLevel = narrativeDetailLevel,
        intimacyDetailLevel = intimacyDetailLevel,
        fadePolicy = fadePolicy,
        presentationMappingSchemaVersion = PRESENTATION_MAPPING_SCHEMA_VERSION,
        contentControlSchemaVersion = CONTENT_CONTROL_SCHEMA_VERSION,
    )
}

private const val MIN_DETAIL_LEVEL = 0
private const val MAX_DETAIL_LEVEL = 4

private fun requireDetailLevel(name: String, value: Int) {
    require(value in MIN_DETAIL_LEVEL..MAX_DETAIL_LEVEL) {
        "$name must be between $MIN_DETAIL_LEVEL and $MAX_DETAIL_LEVEL"
    }
}
