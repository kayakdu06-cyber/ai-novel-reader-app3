package app.zhijuan.core.database.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NarrativeStateDeltaContractTest {
    @Test
    fun `obligation cannot disappear and inactive namespace is rejected`() {
        val result = NarrativeStateDeltaValidatorV1.validate(input(
            priorObligations = listOf(NarrativeObligationV1("promise-1", "归还法宝", 3)),
            obligationUpdates = emptyList(),
            activeNamespaces = setOf(StoryStateNamespaceV1.CHARACTER),
            deltas = listOf(delta(StoryStateNamespaceV1.ITEM, "owner", "\"a\"", "\"b\"", "交付法宝")),
        )) as NarrativeStateValidationResultV1.Invalid
        assertTrue(result.issues.any { it.code == NarrativeStateIssueCodeV1.OBLIGATION_DISAPPEARED })
        assertTrue(result.issues.any { it.code == NarrativeStateIssueCodeV1.INACTIVE_NAMESPACE })
    }

    @Test
    fun `system cannot jump while item and relationship require event evidence`() {
        val result = NarrativeStateDeltaValidatorV1.validate(input(
            activeNamespaces = setOf(StoryStateNamespaceV1.SYSTEM, StoryStateNamespaceV1.ITEM, StoryStateNamespaceV1.RELATIONSHIP),
            deltas = listOf(
                delta(StoryStateNamespaceV1.SYSTEM, "level", "1", "3", "完成一次任务"),
                delta(StoryStateNamespaceV1.ITEM, "owner", "\"a\"", "\"b\"", ""),
                delta(StoryStateNamespaceV1.RELATIONSHIP, "trust", "1", "-1", ""),
            ),
        )) as NarrativeStateValidationResultV1.Invalid
        assertEquals(
            setOf(NarrativeStateIssueCodeV1.SYSTEM_LEVEL_JUMP, NarrativeStateIssueCodeV1.STATE_EVIDENCE_MISSING,
                NarrativeStateIssueCodeV1.ITEM_OWNER_CHANGED_WITHOUT_EVENT, NarrativeStateIssueCodeV1.RELATIONSHIP_CHANGED_WITHOUT_EVENT),
            result.issues.mapTo(mutableSetOf(), NarrativeStateIssueV1::code),
        )
    }

    @Test
    fun `valid relationship rise or fall maps into existing atomic commit rows`() {
        val validation = input(
            priorObligations = listOf(NarrativeObligationV1("promise-1", "兑现承诺", 2)),
            obligationUpdates = listOf(NarrativeObligationUpdateV1("promise-1", NarrativeObligationActionV1.FULFILL, "主角当面兑现承诺")),
            activeNamespaces = setOf(StoryStateNamespaceV1.RELATIONSHIP),
            deltas = listOf(delta(StoryStateNamespaceV1.RELATIONSHIP, "trust", "2", "-1", "背叛事件")),
        )
        assertEquals(NarrativeStateValidationResultV1.Valid, NarrativeStateDeltaValidatorV1.validate(validation))
        val mapped = NarrativeStatePersistenceMapperV1.mapValidated(validation,
            NarrativeStatePersistenceSpecV1("book-1", "version-2", "stage-2", 2, 100))
        assertEquals(1, mapped.obligationFacts.size)
        assertEquals("obligation.promise-1", mapped.obligationFacts.single().conflictGroupId)
        assertEquals("relationship.entity-2.trust", mapped.stateEvents.single().attributeKey)
        assertEquals("-1", mapped.stateEvents.single().newValueJson)
    }

    private fun input(
        activeNamespaces: Set<StoryStateNamespaceV1>,
        priorObligations: List<NarrativeObligationV1> = emptyList(),
        obligationUpdates: List<NarrativeObligationUpdateV1> = emptyList(),
        deltas: List<StoryStateDeltaV1>,
    ) = NarrativeStateValidationInputV1(activeNamespaces, priorObligations, obligationUpdates,
        deltas.associate { it.key to requireNotNull(it.oldValueJson) }, deltas)

    private fun delta(namespace: StoryStateNamespaceV1, attribute: String, old: String, new: String, evidence: String) =
        StoryStateDeltaV1(
            StoryStateKeyV1(
                namespace = namespace,
                entityId = "entity-1",
                attribute = attribute,
                relatedEntityId = if (namespace == StoryStateNamespaceV1.RELATIONSHIP) "entity-2" else null,
            ),
            old,
            new,
            evidence,
        )
}
