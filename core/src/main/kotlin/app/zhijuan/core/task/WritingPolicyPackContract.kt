package app.zhijuan.core.task

import app.zhijuan.core.model.GenerationPhase
import java.security.MessageDigest
import java.util.Collections

enum class PolicyFragmentLayer {
    PLANNING,
    DRAFTING,
    CONTINUITY,
    ANALYSIS,
    REVISION,
    PRESENTATION,
}

enum class WritingPolicyPriority(val rank: Int) {
    SAFETY_AND_ADULT_GATE(1),
    USER_EXPLICIT_SETTING(2),
    AUTHORITATIVE_FACT(3),
    NARRATIVE_OBLIGATION(4),
    STATE_TRANSITION(5),
    PRESENTATION_AND_STYLE(6),
    GENERAL_GUIDANCE(7),
}

class PolicyInstructionV1(
    val id: String,
    val text: String,
) {
    init {
        require(POLICY_ID.matches(id)) { "Policy instruction id is invalid." }
        require(text.isNotBlank()) { "Policy instruction text must not be blank." }
    }

    override fun toString(): String = "PolicyInstructionV1(id=$id, text=<redacted>)"
}

class WritingPolicyFragmentV1(
    val fragmentId: String,
    val layer: PolicyFragmentLayer,
    applicableStages: Set<GenerationPhase>,
    requiredCapabilities: Set<String>,
    forbiddenCapabilities: Set<String>,
    val priority: WritingPolicyPriority,
    val maxPromptChars: Int,
    hardRules: List<PolicyInstructionV1> = emptyList(),
    softGuidance: List<PolicyInstructionV1> = emptyList(),
    structuredOutputFieldIds: Set<String> = emptySet(),
    compatiblePromptSchemaVersions: Set<Int> = setOf(PromptBundleCatalogV1.CONTRACT_SCHEMA_VERSION),
) {
    val applicableStages: Set<GenerationPhase> = immutableSet(applicableStages.sortedBy(GenerationPhase::ordinal))
    val requiredCapabilities: Set<String> = immutableSet(requiredCapabilities.sorted())
    val forbiddenCapabilities: Set<String> = immutableSet(forbiddenCapabilities.sorted())
    val hardRules: List<PolicyInstructionV1> = immutableList(hardRules)
    val softGuidance: List<PolicyInstructionV1> = immutableList(softGuidance)
    val structuredOutputFieldIds: Set<String> = immutableSet(structuredOutputFieldIds.sorted())
    val compatiblePromptSchemaVersions: Set<Int> = immutableSet(compatiblePromptSchemaVersions.sorted())

    init {
        require(POLICY_ID.matches(fragmentId)) { "Policy fragment id is invalid." }
        require(this.applicableStages.isNotEmpty()) { "Policy fragment must declare an applicable stage." }
        require(this.requiredCapabilities.all(POLICY_ID::matches)) { "Required capability id is invalid." }
        require(this.forbiddenCapabilities.all(POLICY_ID::matches)) { "Forbidden capability id is invalid." }
        require(this.requiredCapabilities.intersect(this.forbiddenCapabilities).isEmpty()) {
            "A capability cannot be both required and forbidden."
        }
        require(maxPromptChars > 0) { "Policy fragment prompt budget must be positive." }
        require(this.hardRules.isNotEmpty() xor this.softGuidance.isNotEmpty()) {
            "Policy fragment must declare hard rules or soft guidance, but not both."
        }
        require(this.structuredOutputFieldIds.all(POLICY_ID::matches)) {
            "Structured output field id is invalid."
        }
        require(this.compatiblePromptSchemaVersions.isNotEmpty()) {
            "Policy fragment must declare a compatible prompt schema."
        }
        require(this.compatiblePromptSchemaVersions.all { it > 0 }) {
            "Compatible prompt schema versions must be positive."
        }
        require((this.hardRules + this.softGuidance).map(PolicyInstructionV1::id).distinct().size ==
            this.hardRules.size + this.softGuidance.size
        ) { "Policy instruction ids must be unique within a fragment." }
        require((this.hardRules + this.softGuidance).sumOf { it.text.length } <= maxPromptChars) {
            "Policy instruction text exceeds the fragment prompt budget."
        }
    }

    override fun toString(): String =
        "WritingPolicyFragmentV1(fragmentId=$fragmentId, layer=$layer, priority=$priority, " +
            "hardRuleCount=${hardRules.size}, softGuidanceCount=${softGuidance.size})"
}

data class PolicyPriorityConflictV1(
    val winnerFragmentId: String,
    val loserFragmentId: String,
) {
    init {
        require(POLICY_ID.matches(winnerFragmentId)) { "Conflict winner fragment id is invalid." }
        require(POLICY_ID.matches(loserFragmentId)) { "Conflict loser fragment id is invalid." }
        require(winnerFragmentId != loserFragmentId) { "A fragment cannot conflict with itself." }
    }
}

class WritingPolicyPackV1 private constructor(
    val packId: String,
    val version: String,
    val schemaVersion: Int,
    val locale: String,
    fragments: List<WritingPolicyFragmentV1>,
    validatorIds: Set<String>,
    conflicts: List<PolicyPriorityConflictV1>,
    val promptBudgetChars: Int,
) {
    val fragments: List<WritingPolicyFragmentV1> = immutableList(fragments.sortedBy(WritingPolicyFragmentV1::fragmentId))
    val validatorIds: Set<String> = immutableSet(validatorIds.sorted())
    val conflicts: List<PolicyPriorityConflictV1> = immutableList(
        conflicts.sortedWith(compareBy(PolicyPriorityConflictV1::winnerFragmentId, PolicyPriorityConflictV1::loserFragmentId)),
    )
    val checksum: String

    init {
        require(POLICY_ID.matches(packId)) { "Writing policy pack id is invalid." }
        require(POLICY_VERSION.matches(version)) { "Writing policy pack version is invalid." }
        require(schemaVersion == WritingPolicyPackCatalogV1.SCHEMA_VERSION) {
            "Unsupported writing policy pack schema version."
        }
        require(POLICY_LOCALE.matches(locale)) { "Writing policy pack locale is invalid." }
        require(this.fragments.isNotEmpty()) { "Writing policy pack must contain a fragment." }
        require(this.fragments.map(WritingPolicyFragmentV1::fragmentId).distinct().size == this.fragments.size) {
            "Writing policy fragment ids must be unique."
        }
        require(this.validatorIds.all(POLICY_ID::matches)) { "Policy validator id is invalid." }
        require(promptBudgetChars > 0) { "Writing policy pack prompt budget must be positive." }
        validateConflicts()
        checksum = WritingPolicyPackFingerprintV1.hash(this)
    }

    private fun validateConflicts() {
        val fragmentById = fragments.associateBy(WritingPolicyFragmentV1::fragmentId)
        val seenPairs = mutableSetOf<Set<String>>()
        conflicts.forEach { conflict ->
            val winner = requireNotNull(fragmentById[conflict.winnerFragmentId]) {
                "Unknown conflict winner fragment: ${conflict.winnerFragmentId}"
            }
            val loser = requireNotNull(fragmentById[conflict.loserFragmentId]) {
                "Unknown conflict loser fragment: ${conflict.loserFragmentId}"
            }
            require(winner.priority.rank < loser.priority.rank) {
                "Conflict winner must have a higher priority than the loser."
            }
            require(seenPairs.add(setOf(winner.fragmentId, loser.fragmentId))) {
                "A fragment pair may declare only one priority conflict."
            }
        }
    }

    override fun toString(): String =
        "WritingPolicyPackV1(packId=$packId, version=$version, schemaVersion=$schemaVersion, " +
            "fragmentCount=${fragments.size}, checksum=$checksum)"

    companion object {
        fun create(
            packId: String,
            version: String,
            locale: String,
            fragments: List<WritingPolicyFragmentV1>,
            validatorIds: Set<String> = emptySet(),
            conflicts: List<PolicyPriorityConflictV1> = emptyList(),
            promptBudgetChars: Int,
            schemaVersion: Int = WritingPolicyPackCatalogV1.SCHEMA_VERSION,
        ): WritingPolicyPackV1 = WritingPolicyPackV1(
            packId = packId,
            version = version,
            schemaVersion = schemaVersion,
            locale = locale,
            fragments = fragments,
            validatorIds = validatorIds,
            conflicts = conflicts,
            promptBudgetChars = promptBudgetChars,
        )
    }
}

object WritingPolicyPackCatalogV1 {
    const val SCHEMA_VERSION = 1
    const val CORE_PACK_ID = "zhijuan.web-fiction-core"
    const val CORE_PACK_VERSION = "1.0.0"
}

class PromptBundlePolicyBindingV1 internal constructor(
    val promptBundleVersion: String,
    val promptContractSchemaVersion: Int,
    val policyPackId: String,
    val policyPackVersion: String,
    val policyPackSchemaVersion: Int,
    val policyPackChecksum: String,
    selectedFragmentIds: List<String>,
) {
    val selectedFragmentIds: List<String> = immutableList(selectedFragmentIds.sorted())

    override fun toString(): String =
        "PromptBundlePolicyBindingV1(promptBundleVersion=$promptBundleVersion, " +
            "policyPackId=$policyPackId, policyPackVersion=$policyPackVersion, " +
            "selectedFragmentIds=$selectedFragmentIds)"
}

object WritingPolicyPackPromptBundleAdapterV1 {
    fun bind(
        pack: WritingPolicyPackV1,
        selectedFragmentIds: Collection<String>,
        promptBundleVersion: String = PromptBundleCatalogV1.BUNDLE_VERSION,
        promptContractSchemaVersion: Int = PromptBundleCatalogV1.CONTRACT_SCHEMA_VERSION,
    ): PromptBundlePolicyBindingV1 {
        require(promptBundleVersion == PromptBundleCatalogV1.BUNDLE_VERSION) {
            "Unsupported prompt bundle version."
        }
        require(promptContractSchemaVersion == PromptBundleCatalogV1.CONTRACT_SCHEMA_VERSION) {
            "Unsupported prompt contract schema version."
        }
        require(pack.packId == WritingPolicyPackCatalogV1.CORE_PACK_ID) {
            "Unsupported writing policy pack."
        }
        require(pack.version == WritingPolicyPackCatalogV1.CORE_PACK_VERSION) {
            "Unsupported writing policy pack version."
        }
        require(pack.schemaVersion == WritingPolicyPackCatalogV1.SCHEMA_VERSION) {
            "Unsupported writing policy pack schema version."
        }

        val selected = selectedFragmentIds.toSet()
        val fragmentById = pack.fragments.associateBy(WritingPolicyFragmentV1::fragmentId)
        require(selected.all(fragmentById::containsKey)) { "Unknown writing policy fragment." }
        require(selected.all { fragmentId ->
            promptContractSchemaVersion in requireNotNull(fragmentById[fragmentId]).compatiblePromptSchemaVersions
        }) { "Writing policy fragment is incompatible with the prompt contract schema." }

        return PromptBundlePolicyBindingV1(
            promptBundleVersion = promptBundleVersion,
            promptContractSchemaVersion = promptContractSchemaVersion,
            policyPackId = pack.packId,
            policyPackVersion = pack.version,
            policyPackSchemaVersion = pack.schemaVersion,
            policyPackChecksum = pack.checksum,
            selectedFragmentIds = selected.toList(),
        )
    }
}

private object WritingPolicyPackFingerprintV1 {
    private const val DOMAIN = "zhijuan.writing-policy-pack.v1"

    fun hash(pack: WritingPolicyPackV1): String {
        val canonical = StringBuilder()
        fun field(value: String) {
            canonical.append(value.length).append(':').append(value)
        }
        fun strings(values: Collection<String>) {
            field(values.size.toString())
            values.forEach(::field)
        }

        field(DOMAIN)
        field(pack.packId)
        field(pack.version)
        field(pack.schemaVersion.toString())
        field(pack.locale)
        field(pack.promptBudgetChars.toString())
        strings(pack.validatorIds)
        field(pack.fragments.size.toString())
        pack.fragments.forEach { fragment ->
            field(fragment.fragmentId)
            field(fragment.layer.name)
            strings(fragment.applicableStages.map(GenerationPhase::name))
            strings(fragment.requiredCapabilities)
            strings(fragment.forbiddenCapabilities)
            field(fragment.priority.name)
            field(fragment.priority.rank.toString())
            field(fragment.maxPromptChars.toString())
            strings(fragment.structuredOutputFieldIds)
            strings(fragment.compatiblePromptSchemaVersions.map(Int::toString))
            instructions("hard", fragment.hardRules, ::field)
            instructions("soft", fragment.softGuidance, ::field)
        }
        field(pack.conflicts.size.toString())
        pack.conflicts.forEach { conflict ->
            field(conflict.winnerFragmentId)
            field(conflict.loserFragmentId)
        }

        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun instructions(
        kind: String,
        values: List<PolicyInstructionV1>,
        field: (String) -> Unit,
    ) {
        field(kind)
        field(values.size.toString())
        values.forEach { instruction ->
            field(instruction.id)
            field(instruction.text)
        }
    }
}

private val POLICY_ID = Regex("[a-z0-9][a-z0-9._-]{0,127}")
private val POLICY_VERSION = Regex("[0-9]+(?:\\.[0-9]+){0,2}(?:-[a-z0-9.-]+)?")
private val POLICY_LOCALE = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{2,8})*")

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(values.toList())

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
