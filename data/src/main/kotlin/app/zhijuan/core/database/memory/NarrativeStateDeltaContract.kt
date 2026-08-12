package app.zhijuan.core.database.memory

import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.DerivedDataStatus
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class NarrativeObligationActionV1 { CARRY_FORWARD, PROGRESS, FULFILL, POSTPONE, CANCEL }

data class NarrativeObligationV1(
    val obligationId: String,
    val description: String,
    val dueChapterIndex: Int?,
) {
    init {
        require(ID.matches(obligationId) && description.isNotBlank()) { "Narrative obligation is invalid." }
        require(dueChapterIndex == null || dueChapterIndex >= 1) { "Narrative obligation due chapter is invalid." }
    }
}

data class NarrativeObligationUpdateV1(
    val obligationId: String,
    val action: NarrativeObligationActionV1,
    val evidence: String,
    val nextDueChapterIndex: Int? = null,
) {
    init {
        require(ID.matches(obligationId)) { "Narrative obligation update id is invalid." }
        require(nextDueChapterIndex == null || nextDueChapterIndex >= 1) {
            "Narrative obligation update due chapter is invalid."
        }
    }
}

enum class StoryStateNamespaceV1 { CHARACTER, RELATIONSHIP, ITEM, SYSTEM, CULTIVATION, WORLD }

data class StoryStateKeyV1(
    val namespace: StoryStateNamespaceV1,
    val entityId: String,
    val attribute: String,
    val relatedEntityId: String? = null,
) {
    init {
        require(ID.matches(entityId) && STATE_ATTRIBUTE.matches(attribute)) { "Story state key is invalid." }
        require(relatedEntityId == null || ID.matches(relatedEntityId)) { "Related entity id is invalid." }
        require(
            (namespace == StoryStateNamespaceV1.RELATIONSHIP) == (relatedEntityId != null),
        ) { "Relationship state requires exactly one related entity; other namespaces forbid it." }
        require(relatedEntityId != entityId) { "Relationship state cannot target the same entity." }
    }
}

data class StoryStateDeltaV1(
    val key: StoryStateKeyV1,
    val oldValueJson: String?,
    val newValueJson: String,
    val evidence: String,
)

data class NarrativeStateValidationInputV1(
    val activeNamespaces: Set<StoryStateNamespaceV1>,
    val priorObligations: List<NarrativeObligationV1>,
    val obligationUpdates: List<NarrativeObligationUpdateV1>,
    val currentStateValues: Map<StoryStateKeyV1, String>,
    val stateDeltas: List<StoryStateDeltaV1>,
)

enum class NarrativeStateIssueCodeV1 {
    OBLIGATION_DISAPPEARED,
    DUPLICATE_OBLIGATION_UPDATE,
    OBLIGATION_EVIDENCE_MISSING,
    POSTPONED_DUE_CHAPTER_INVALID,
    INACTIVE_NAMESPACE,
    DUPLICATE_STATE_DELTA,
    STATE_SOURCE_MISMATCH,
    STATE_EVIDENCE_MISSING,
    SYSTEM_LEVEL_JUMP,
    ITEM_OWNER_CHANGED_WITHOUT_EVENT,
    RELATIONSHIP_CHANGED_WITHOUT_EVENT,
}

data class NarrativeStateIssueV1(val code: NarrativeStateIssueCodeV1, val reference: String)

sealed interface NarrativeStateValidationResultV1 {
    data object Valid : NarrativeStateValidationResultV1
    data class Invalid(val issues: List<NarrativeStateIssueV1>) : NarrativeStateValidationResultV1
}

object NarrativeStateDeltaValidatorV1 {
    fun validate(input: NarrativeStateValidationInputV1): NarrativeStateValidationResultV1 {
        val issues = buildList {
            val updatesById = input.obligationUpdates.groupBy(NarrativeObligationUpdateV1::obligationId)
            input.priorObligations.forEach { obligation ->
                val updates = updatesById[obligation.obligationId].orEmpty()
                if (updates.isEmpty()) add(issue(NarrativeStateIssueCodeV1.OBLIGATION_DISAPPEARED, obligation.obligationId))
                if (updates.size > 1) add(issue(NarrativeStateIssueCodeV1.DUPLICATE_OBLIGATION_UPDATE, obligation.obligationId))
            }
            input.obligationUpdates.forEach { update ->
                if (update.evidence.isBlank()) add(issue(NarrativeStateIssueCodeV1.OBLIGATION_EVIDENCE_MISSING, update.obligationId))
                if (update.action == NarrativeObligationActionV1.POSTPONE && update.nextDueChapterIndex == null) {
                    add(issue(NarrativeStateIssueCodeV1.POSTPONED_DUE_CHAPTER_INVALID, update.obligationId))
                }
            }

            input.stateDeltas.groupBy(StoryStateDeltaV1::key).forEach { (key, deltas) ->
                if (deltas.size > 1) add(issue(NarrativeStateIssueCodeV1.DUPLICATE_STATE_DELTA, key.reference()))
            }
            input.stateDeltas.forEach { delta ->
                val reference = delta.key.reference()
                if (delta.key.namespace !in input.activeNamespaces) {
                    add(issue(NarrativeStateIssueCodeV1.INACTIVE_NAMESPACE, reference))
                }
                if (input.currentStateValues[delta.key] != delta.oldValueJson) {
                    add(issue(NarrativeStateIssueCodeV1.STATE_SOURCE_MISMATCH, reference))
                }
                if (delta.evidence.isBlank()) add(issue(NarrativeStateIssueCodeV1.STATE_EVIDENCE_MISSING, reference))
                if (delta.key.namespace == StoryStateNamespaceV1.SYSTEM && delta.key.attribute == "level") {
                    val old = delta.oldValueJson?.jsonInt()
                    val new = delta.newValueJson.jsonInt()
                    if (old == null || new == null || new !in old..old + 1) {
                        add(issue(NarrativeStateIssueCodeV1.SYSTEM_LEVEL_JUMP, reference))
                    }
                }
                if (delta.key.namespace == StoryStateNamespaceV1.ITEM && delta.key.attribute == "owner" &&
                    delta.oldValueJson != delta.newValueJson && delta.evidence.isBlank()
                ) add(issue(NarrativeStateIssueCodeV1.ITEM_OWNER_CHANGED_WITHOUT_EVENT, reference))
                if (delta.key.namespace == StoryStateNamespaceV1.RELATIONSHIP &&
                    delta.oldValueJson != delta.newValueJson && delta.evidence.isBlank()
                ) add(issue(NarrativeStateIssueCodeV1.RELATIONSHIP_CHANGED_WITHOUT_EVENT, reference))
            }
        }.distinct()
        return if (issues.isEmpty()) NarrativeStateValidationResultV1.Valid
        else NarrativeStateValidationResultV1.Invalid(issues)
    }

    private fun issue(code: NarrativeStateIssueCodeV1, reference: String) = NarrativeStateIssueV1(code, reference)
    private fun StoryStateKeyV1.reference() =
        listOfNotNull(namespace.name.lowercase(), entityId, relatedEntityId, attribute).joinToString(":")
    private fun String.jsonInt(): Int? = runCatching { Json.parseToJsonElement(this).jsonPrimitive.intOrNull }.getOrNull()
}

data class NarrativeStatePersistenceSpecV1(
    val bookId: String,
    val chapterVersionId: String,
    val generationStageId: String,
    val chapterIndex: Int,
    val createdAt: Long,
)

data class NarrativeStatePersistenceDraftV1(
    val obligationFacts: List<CanonFactEntity>,
    val stateEvents: List<EntityEventEntity>,
)

object NarrativeStatePersistenceMapperV1 {
    fun mapValidated(
        input: NarrativeStateValidationInputV1,
        spec: NarrativeStatePersistenceSpecV1,
    ): NarrativeStatePersistenceDraftV1 {
        require(NarrativeStateDeltaValidatorV1.validate(input) == NarrativeStateValidationResultV1.Valid) {
            "Narrative obligation or state delta is invalid."
        }
        require(spec.chapterIndex >= 1 && spec.createdAt >= 0 &&
            listOf(spec.bookId, spec.chapterVersionId, spec.generationStageId).all(ID::matches)
        ) { "Narrative state persistence source is invalid." }
        val baseOrder = Math.multiplyExact(spec.chapterIndex.toLong(), 1_000_000L)
        val facts = input.obligationUpdates.sortedBy(NarrativeObligationUpdateV1::obligationId).mapIndexed { index, update ->
            val payload = JsonObject(linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "kind" to JsonPrimitive("NARRATIVE_OBLIGATION"),
                "obligationId" to JsonPrimitive(update.obligationId),
                "action" to JsonPrimitive(update.action.name),
                "evidence" to JsonPrimitive(update.evidence),
                "nextDueChapterIndex" to (update.nextDueChapterIndex?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
            )).toString()
            CanonFactEntity(
                canonFactId = stableId("obligation", spec, update.obligationId),
                bookId = spec.bookId,
                entityId = null,
                factText = "Narrative obligation ${update.obligationId}: ${update.action.name}",
                factPayloadJson = payload,
                canonLevel = CanonLevel.STORY_CANON,
                scopeJson = "{\"fromChapter\":${spec.chapterIndex},\"throughChapter\":null}",
                sourceChapterVersionId = spec.chapterVersionId,
                sourceBibleRevisionId = null,
                validFromStoryOrder = baseOrder + index + 1L,
                validToStoryOrder = null,
                conflictGroupId = "obligation.${update.obligationId}",
                status = DerivedDataStatus.VALID,
                createdAt = spec.createdAt,
            )
        }
        val events = input.stateDeltas.sortedBy { "${it.key.namespace}:${it.key.entityId}:${it.key.attribute}" }
            .mapIndexed { index, delta ->
                EntityEventEntity(
                    entityEventId = stableId("state", spec, delta.key.reference()),
                    bookId = spec.bookId,
                    entityId = delta.key.entityId,
                    sourceChapterVersionId = spec.chapterVersionId,
                    storyOrder = baseOrder + facts.size + index + 1L,
                    attributeKey = listOfNotNull(
                        delta.key.namespace.name.lowercase(),
                        delta.key.relatedEntityId,
                        delta.key.attribute,
                    ).joinToString("."),
                    oldValueJson = delta.oldValueJson,
                    newValueJson = delta.newValueJson,
                    storyTimeExpression = null,
                    confidenceMicros = 1_000_000,
                    canonLevel = CanonLevel.STORY_CANON,
                    evidenceJson = JsonObject(mapOf(
                        "source" to JsonPrimitive("narrative-state-delta.v1"),
                        "generationStageId" to JsonPrimitive(spec.generationStageId),
                        "relatedEntityId" to (
                            delta.key.relatedEntityId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull
                        ),
                        "evidence" to JsonPrimitive(delta.evidence),
                    )).toString(),
                    status = DerivedDataStatus.VALID,
                    createdAt = spec.createdAt,
                )
            }
        return NarrativeStatePersistenceDraftV1(facts, events)
    }

    private fun stableId(prefix: String, spec: NarrativeStatePersistenceSpecV1, value: String): String =
        "narrative.$prefix.${sha256("${spec.generationStageId}\u0000${spec.chapterVersionId}\u0000$value").take(32)}"
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun StoryStateKeyV1.reference() =
        listOfNotNull(namespace.name.lowercase(), entityId, relatedEntityId, attribute).joinToString(":")
}

private val ID = Regex("[A-Za-z0-9._:-]{1,128}")
private val STATE_ATTRIBUTE = Regex("[a-z][a-z0-9._-]{0,95}")
