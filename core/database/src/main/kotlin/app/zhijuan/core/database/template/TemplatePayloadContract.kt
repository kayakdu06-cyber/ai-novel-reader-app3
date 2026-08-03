package app.zhijuan.core.database.template

internal object TemplatePayloadContract {
    private const val MAX_SEGMENT_CHARACTERS = 256_000
    private const val MAX_TOTAL_CHARACTERS = 1_000_000

    private val forbiddenJsonKey = Regex(
        pattern = """(?i)[\"']?(?:api[_-]?key|authorization|secret|access[_-]?token|chapter[_-]?(?:content|text|version)|stream[_-]?draft|request[_-]?attempt|usage[_-]?ledger|reading[_-]?progress)[\"']?\s*:""",
    )
    private val credentialValue = Regex(
        pattern = """(?i)(?:bearer\s+[a-z0-9._-]{12,}|(?:sk|key)-[a-z0-9_-]{16,})""",
    )

    fun validate(revision: TemplateRevisionEntity) {
        validateSegments(revision.payloadSegments())
        require(revision.extractionModelSnapshotJson?.let(::isSafe) != false) {
            "Extraction model snapshot contains a forbidden credential or runtime field."
        }
    }

    fun validate(snapshot: TemplateUseSnapshotEntity) {
        validateSegments(snapshot.payloadSegments() + snapshot.userOverridesJson + snapshot.capabilityResolutionJson)
        require(isSafe(snapshot.sourceProvenanceJson)) {
            "Template provenance contains a forbidden credential or runtime field."
        }
    }

    private fun validateSegments(segments: List<String>) {
        require(segments.all { it.length <= MAX_SEGMENT_CHARACTERS }) {
            "A template payload segment is too large."
        }
        require(segments.sumOf(String::length) <= MAX_TOTAL_CHARACTERS) {
            "The template payload is too large."
        }
        require(segments.all(::isSafe)) {
            "Template payload contains a forbidden credential, chapter body, log, or runtime field."
        }
    }

    private fun isSafe(value: String): Boolean =
        !forbiddenJsonKey.containsMatchIn(value) && !credentialValue.containsMatchIn(value)

    private fun TemplateRevisionEntity.payloadSegments(): List<String> = listOf(
        storySeedJson,
        genreJson,
        stableCharactersJson,
        worldRulesJson,
        writingStyleJson,
        structureJson,
        presentationJson,
        contentRulesJson,
        generationStrategyJson,
        modelRolePreferencesJson,
        extensionJson,
    )

    private fun TemplateUseSnapshotEntity.payloadSegments(): List<String> = listOf(
        storySeedJson,
        genreJson,
        stableCharactersJson,
        worldRulesJson,
        writingStyleJson,
        structureJson,
        presentationJson,
        contentRulesJson,
        generationStrategyJson,
        modelRolePreferencesJson,
        extensionJson,
    )
}
