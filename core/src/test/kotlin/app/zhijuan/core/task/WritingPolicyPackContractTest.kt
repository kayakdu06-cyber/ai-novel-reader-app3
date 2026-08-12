package app.zhijuan.core.task

import app.zhijuan.core.model.GenerationPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WritingPolicyPackContractTest {
    @Test
    fun `canonical hash is stable and constructor inputs cannot mutate the pack`() {
        val fragments = mutableListOf(fragment("draft.base", WritingPolicyPriority.GENERAL_GUIDANCE))
        val first = pack(fragments = fragments, validatorIds = linkedSetOf("validator.z", "validator.a"))
        fragments.clear()
        val second = pack(
            fragments = listOf(fragment("draft.base", WritingPolicyPriority.GENERAL_GUIDANCE)),
            validatorIds = linkedSetOf("validator.a", "validator.z"),
        )

        assertEquals(first.checksum, second.checksum)
        assertEquals(listOf("draft.base"), first.fragments.map(WritingPolicyFragmentV1::fragmentId))
    }

    @Test
    fun `adapter rejects an unknown pack version or fragment`() {
        assertThrows<IllegalArgumentException> {
            WritingPolicyPackPromptBundleAdapterV1.bind(
                pack(version = "2.0.0"),
                selectedFragmentIds = listOf("draft.base"),
            )
        }
        assertThrows<IllegalArgumentException> {
            WritingPolicyPackPromptBundleAdapterV1.bind(
                pack(),
                selectedFragmentIds = listOf("missing.fragment"),
            )
        }
    }

    @Test
    fun `priority conflict rejects a lower priority winner`() {
        val high = fragment("fact.high", WritingPolicyPriority.AUTHORITATIVE_FACT)
        val low = fragment("style.low", WritingPolicyPriority.PRESENTATION_AND_STYLE)

        assertThrows<IllegalArgumentException> {
            pack(
                fragments = listOf(high, low),
                conflicts = listOf(PolicyPriorityConflictV1(low.fragmentId, high.fragmentId)),
            )
        }
    }

    @Test
    fun `contract string forms never expose instruction text`() {
        val secret = "SENSITIVE_POLICY_TEXT_8c4429"
        val instruction = PolicyInstructionV1("rule.secret", secret)
        val fragment = fragment("draft.base", instruction = instruction)
        val policyPack = pack(fragments = listOf(fragment))
        val binding = WritingPolicyPackPromptBundleAdapterV1.bind(
            policyPack,
            selectedFragmentIds = listOf(fragment.fragmentId),
        )

        listOf(instruction, fragment, policyPack, binding).forEach { contract ->
            assertFalse(contract.toString().contains(secret))
        }
    }

    private fun pack(
        version: String = WritingPolicyPackCatalogV1.CORE_PACK_VERSION,
        fragments: List<WritingPolicyFragmentV1> = listOf(
            fragment("draft.base", WritingPolicyPriority.GENERAL_GUIDANCE),
        ),
        validatorIds: Set<String> = emptySet(),
        conflicts: List<PolicyPriorityConflictV1> = emptyList(),
    ): WritingPolicyPackV1 = WritingPolicyPackV1.create(
        packId = WritingPolicyPackCatalogV1.CORE_PACK_ID,
        version = version,
        locale = "zh-CN",
        fragments = fragments,
        validatorIds = validatorIds,
        conflicts = conflicts,
        promptBudgetChars = 4_096,
    )

    private fun fragment(
        id: String,
        priority: WritingPolicyPriority = WritingPolicyPriority.GENERAL_GUIDANCE,
        instruction: PolicyInstructionV1 = PolicyInstructionV1("rule.$id", "keep the chapter logically consistent"),
    ): WritingPolicyFragmentV1 = WritingPolicyFragmentV1(
        fragmentId = id,
        layer = PolicyFragmentLayer.DRAFTING,
        applicableStages = setOf(GenerationPhase.DRAFT_CHAPTER),
        requiredCapabilities = emptySet(),
        forbiddenCapabilities = emptySet(),
        priority = priority,
        maxPromptChars = 256,
        softGuidance = listOf(instruction),
    )
}
