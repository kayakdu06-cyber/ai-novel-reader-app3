package app.zhijuan.feature.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.RelevantCharacterAdultGate
import app.zhijuan.core.task.PolicyFragmentLayer
import app.zhijuan.core.task.PolicyInstructionV1
import app.zhijuan.core.task.PromptBundlePolicyBindingV1
import app.zhijuan.core.task.WritingPolicyFragmentV1
import app.zhijuan.core.task.WritingPolicyPackCatalogV1
import app.zhijuan.core.task.WritingPolicyPackPromptBundleAdapterV1
import app.zhijuan.core.task.WritingPolicyPackV1
import app.zhijuan.core.task.WritingPolicyPriority
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CreationSnapshotIntentSourceV1(
    val sourceContentHash: String,
    val rawInputJson: String,
    val normalizedInputJson: String,
) {
    init {
        require(SHA_256.matches(sourceContentHash)) { "Creation snapshot content hash is invalid." }
        require(rawInputJson.isNotBlank() && normalizedInputJson.isNotBlank()) {
            "Creation snapshot intent JSON must not be blank."
        }
    }

    override fun toString(): String =
        "CreationSnapshotIntentSourceV1(sourceContentHash=redacted, content=redacted)"
}

class OpenCreativeIntentV1 internal constructor(
    val sourceContentHash: String,
    val rawStoryIdea: String,
    val normalizedStoryIdea: String,
    val requestedGenreId: String?,
    rawAdvancedDetails: Map<String, String>,
    normalizedAdvancedDetails: Map<String, String>,
) {
    val rawAdvancedDetails: Map<String, String> = immutableMap(rawAdvancedDetails)
    val normalizedAdvancedDetails: Map<String, String> = immutableMap(normalizedAdvancedDetails)

    private val promptText: String = buildList {
        add("故事设想：$rawStoryIdea")
        ADVANCED_DETAIL_FIELDS.forEach { field ->
            rawAdvancedDetails[field]
                ?.takeIf(String::isNotBlank)
                ?.let { add("${ADVANCED_DETAIL_LABELS.getValue(field)}：$it") }
        }
        requestedGenreId?.let { add("用户选择的快捷题材 ID：$it") }
    }.joinToString("\n")

    init {
        require(SHA_256.matches(sourceContentHash)) { "Creative intent source hash is invalid." }
        require(rawStoryIdea.isNotBlank() && normalizedStoryIdea.isNotBlank()) {
            "Creative intent story idea must not be blank."
        }
    }

    fun <T> withPromptText(block: (String) -> T): T = block(promptText)

    internal fun inferenceText(): String = buildList {
        add(normalizedStoryIdea)
        addAll(normalizedAdvancedDetails.values)
        requestedGenreId?.let(::add)
    }.joinToString("\n")

    override fun toString(): String =
        "OpenCreativeIntentV1(sourceContentHash=redacted, requestedGenreId=$requestedGenreId, " +
            "advancedFieldCount=${rawAdvancedDetails.size}, content=redacted)"
}

class CapabilityAdapterV1(
    val capabilityId: String,
    stateNamespaceIds: Set<String>,
    requiredPolicyFragmentIds: Set<String>,
    signalTerms: Set<String>,
    val alwaysActive: Boolean = false,
) {
    val stateNamespaceIds: Set<String> = immutableSet(stateNamespaceIds.sorted())
    val requiredPolicyFragmentIds: Set<String> = immutableSet(requiredPolicyFragmentIds.sorted())
    internal val signalTerms: Set<String> = immutableSet(signalTerms.sorted())

    init {
        require(CAPABILITY_ID.matches(capabilityId)) { "Capability id is invalid." }
        require(stateNamespaceIds.all(CAPABILITY_ID::matches)) { "State namespace id is invalid." }
        require(requiredPolicyFragmentIds.all(CAPABILITY_ID::matches)) { "Policy fragment id is invalid." }
        require(signalTerms.none(String::isBlank)) { "Capability signal term must not be blank." }
        require(alwaysActive || signalTerms.isNotEmpty()) {
            "A non-baseline capability must declare an inference signal."
        }
    }
}

object BuiltInStateCapabilityCatalogV1 {
    const val VERSION = "zhijuan.state-capability-catalog.v1"

    val adapters: List<CapabilityAdapterV1> = immutableList(
        listOf(
            adapter("core-narrative", "narrative", alwaysActive = true),
            adapter("character-continuity", "character", alwaysActive = true),
            adapter(
                "relationship-progression",
                "relationship",
                "关系", "恋爱", "爱情", "爱人", "伴侣", "道侣", "婚姻", "信任",
            ),
            adapter(
                "cultivation",
                "cultivation",
                "修仙", "仙侠", "境界", "功法", "灵根", "宗门", "飞升", "xianxia",
            ),
            adapter(
                "progression-system",
                "system",
                "系统", "面板", "任务奖励", "积分升级", "冷却", "等级提升",
            ),
            adapter(
                "item-progression",
                "item",
                "道具", "法宝", "武器", "装备", "物品", "宝物", "本命法器",
            ),
            adapter(
                "mystery",
                "mystery",
                "悬疑", "推理", "线索", "嫌疑", "凶手", "案件", "侦探", "mystery",
            ),
            adapter(
                "faction-politics",
                "faction",
                "阵营", "派系", "同盟", "政治", "朝堂", "势力", "权谋",
            ),
            adapter(
                "intimacy-continuity",
                "intimacy",
                "亲密", "性关系", "成人关系", "身体关系", "情欲",
            ),
            adapter(
                "romance",
                "romance",
                "恋爱", "爱情", "爱人", "伴侣", "道侣", "感情", "暗恋", "romance",
            ),
        ),
    )

    private val byId = adapters.associateBy(CapabilityAdapterV1::capabilityId)

    fun find(capabilityId: String): CapabilityAdapterV1? = byId[capabilityId]

    internal fun infer(text: String): Set<String> = immutableSet(
        adapters.asSequence()
            .filter { adapter -> adapter.alwaysActive || adapter.signalTerms.any(text::contains) }
            .map(CapabilityAdapterV1::capabilityId)
            .sorted()
            .toList(),
    )

    private fun adapter(
        capabilityId: String,
        stateNamespaceId: String,
        vararg signalTerms: String,
        alwaysActive: Boolean = false,
    ) = CapabilityAdapterV1(
        capabilityId = capabilityId,
        stateNamespaceIds = setOf(stateNamespaceId),
        requiredPolicyFragmentIds = setOf("policy.$capabilityId.v1"),
        signalTerms = signalTerms.toSet(),
        alwaysActive = alwaysActive,
    )
}

class BookCapabilityManifestV1 internal constructor(
    val sourceContentHash: String,
    val adapterCatalogVersion: String,
    capabilityIds: Set<String>,
    inferenceReasonCodes: Set<String>,
) {
    val capabilityIds: Set<String> = immutableSet(capabilityIds.sorted())
    val inferenceReasonCodes: Set<String> = immutableSet(inferenceReasonCodes.sorted())
    val manifestHash: String = canonicalHash(
        domain = "zhijuan.book-capability-manifest.v1",
        fields = buildList {
            add("source-content-hash")
            add(sourceContentHash)
            add("adapter-catalog-version")
            add(adapterCatalogVersion)
            add("capability-ids")
            add(this@BookCapabilityManifestV1.capabilityIds.size.toString())
            addAll(this@BookCapabilityManifestV1.capabilityIds)
            add("inference-reason-codes")
            add(this@BookCapabilityManifestV1.inferenceReasonCodes.size.toString())
            addAll(this@BookCapabilityManifestV1.inferenceReasonCodes)
        },
    )

    init {
        require(SHA_256.matches(sourceContentHash)) { "Capability manifest source hash is invalid." }
        require(adapterCatalogVersion == BuiltInStateCapabilityCatalogV1.VERSION) {
            "Capability manifest catalog version is unsupported."
        }
        require(this.capabilityIds.isNotEmpty()) { "Capability manifest must not be empty." }
        require(this.capabilityIds.all { BuiltInStateCapabilityCatalogV1.find(it) != null }) {
            "Capability manifest contains an unknown adapter."
        }
    }

    override fun toString(): String =
        "BookCapabilityManifestV1(adapterCatalogVersion=$adapterCatalogVersion, " +
            "capabilityIds=$capabilityIds, hashes=redacted)"
}

class BookCapabilityRoutingResultV1 internal constructor(
    val creativeIntent: OpenCreativeIntentV1,
    val manifest: BookCapabilityManifestV1,
) {
    init {
        require(creativeIntent.sourceContentHash == manifest.sourceContentHash) {
            "Creative intent and capability manifest sources differ."
        }
    }

    override fun toString(): String =
        "BookCapabilityRoutingResultV1(manifest=$manifest, creativeIntent=redacted)"
}

object BookCapabilityRouterV1 {
    fun derive(source: CreationSnapshotIntentSourceV1): BookCapabilityRoutingResultV1 {
        val raw = parseObject(source.rawInputJson, "Raw creation input")
        val normalized = parseObject(source.normalizedInputJson, "Normalized creation input")
        val intent = OpenCreativeIntentV1(
            sourceContentHash = source.sourceContentHash,
            rawStoryIdea = raw.requiredString("storyIdea"),
            normalizedStoryIdea = normalized.requiredString("storyIdea"),
            requestedGenreId = raw.optionalString("requestedGenreId"),
            rawAdvancedDetails = raw.optionalStringObject("advancedDetails"),
            normalizedAdvancedDetails = normalized.optionalStringObject("advancedDetails"),
        )
        val capabilities = BuiltInStateCapabilityCatalogV1.infer(intent.inferenceText())
        val baselineIds = BuiltInStateCapabilityCatalogV1.adapters
            .filter(CapabilityAdapterV1::alwaysActive)
            .map(CapabilityAdapterV1::capabilityId)
            .toSet()
        val reasonCodes = capabilities.mapTo(linkedSetOf()) { capabilityId ->
            if (capabilityId in baselineIds) {
                "baseline:$capabilityId"
            } else {
                "intent-signal:$capabilityId"
            }
        }
        return BookCapabilityRoutingResultV1(
            creativeIntent = intent,
            manifest = BookCapabilityManifestV1(
                sourceContentHash = source.sourceContentHash,
                adapterCatalogVersion = BuiltInStateCapabilityCatalogV1.VERSION,
                capabilityIds = capabilities,
                inferenceReasonCodes = reasonCodes,
            ),
        )
    }
}

class ChapterCapabilityRequestV1(
    val phase: GenerationPhase,
    val chapterTaskText: String,
    obligationTexts: List<String> = emptyList(),
    val previousConsequenceText: String = "",
    explicitlyRequiredCapabilityIds: Set<String> = emptySet(),
    val intimacyRelevant: Boolean = false,
    val adultGate: RelevantCharacterAdultGate = RelevantCharacterAdultGate.UNKNOWN,
    val availablePolicyPromptChars: Int,
) {
    val obligationTexts: List<String> = immutableList(obligationTexts)
    val explicitlyRequiredCapabilityIds: Set<String> =
        immutableSet(explicitlyRequiredCapabilityIds.sorted())

    init {
        require(chapterTaskText.isNotBlank()) { "Chapter task must not be blank." }
        require(availablePolicyPromptChars > 0) { "Policy prompt budget must be positive." }
    }

    internal fun inferenceText(): String =
        (listOf(chapterTaskText, previousConsequenceText) + obligationTexts).joinToString("\n")

    internal fun bindingHash(): String = canonicalHash(
        domain = "zhijuan.chapter-capability-request.v1",
        fields = buildList {
            add("phase")
            add(phase.name)
            add("chapter-task")
            add(chapterTaskText)
            add("obligations")
            add(obligationTexts.size.toString())
            addAll(obligationTexts)
            add("previous-consequence")
            add(previousConsequenceText)
            add("explicit-capability-ids")
            add(explicitlyRequiredCapabilityIds.size.toString())
            addAll(explicitlyRequiredCapabilityIds)
            add("intimacy-relevant")
            add(intimacyRelevant.toString())
            add("adult-gate")
            add(adultGate.name)
            add("available-policy-prompt-chars")
            add(availablePolicyPromptChars.toString())
        },
    )

    override fun toString(): String =
        "ChapterCapabilityRequestV1(phase=$phase, explicitCapabilityIds=$explicitlyRequiredCapabilityIds, " +
            "intimacyRelevant=$intimacyRelevant, adultGate=$adultGate, content=redacted)"
}

enum class ChapterCapabilityBlockReason {
    UNKNOWN_REQUIRED_ADAPTER,
    REQUIRED_ADAPTER_NOT_IN_MANIFEST,
    ADULT_STATUS_UNKNOWN,
    ADULT_STATUS_NOT_CONFIRMED,
    POLICY_BUDGET_EXCEEDED,
}

class ChapterCapabilityActivationV1 internal constructor(
    val sourceManifestHash: String,
    val phase: GenerationPhase,
    val requestBindingHash: String,
    activeCapabilityIds: Set<String>,
    requiredPolicyFragmentIds: Set<String>,
    expectedStateNamespaceIds: Set<String>,
    forbiddenTransitions: Set<String>,
    promptBudgetByFragment: Map<String, Int>,
    activationReasonCodes: Set<String>,
) {
    val activeCapabilityIds: Set<String> = immutableSet(activeCapabilityIds.sorted())
    val requiredPolicyFragmentIds: Set<String> = immutableSet(requiredPolicyFragmentIds.sorted())
    val expectedStateNamespaceIds: Set<String> = immutableSet(expectedStateNamespaceIds.sorted())
    val forbiddenTransitions: Set<String> = immutableSet(forbiddenTransitions.sorted())
    val promptBudgetByFragment: Map<String, Int> = immutableMap(promptBudgetByFragment.toSortedMap())
    val activationReasonCodes: Set<String> = immutableSet(activationReasonCodes.sorted())
    val activationHash: String = canonicalHash(
        domain = "zhijuan.chapter-capability-activation.v1",
        fields = buildList {
            add("source-manifest-hash")
            add(sourceManifestHash)
            add("phase")
            add(phase.name)
            add("request-binding-hash")
            add(requestBindingHash)
            add("active-capability-ids")
            add(this@ChapterCapabilityActivationV1.activeCapabilityIds.size.toString())
            addAll(this@ChapterCapabilityActivationV1.activeCapabilityIds)
            add("required-policy-fragment-ids")
            add(this@ChapterCapabilityActivationV1.requiredPolicyFragmentIds.size.toString())
            addAll(this@ChapterCapabilityActivationV1.requiredPolicyFragmentIds)
            add("expected-state-namespace-ids")
            add(this@ChapterCapabilityActivationV1.expectedStateNamespaceIds.size.toString())
            addAll(this@ChapterCapabilityActivationV1.expectedStateNamespaceIds)
            add("forbidden-transitions")
            add(this@ChapterCapabilityActivationV1.forbiddenTransitions.size.toString())
            addAll(this@ChapterCapabilityActivationV1.forbiddenTransitions)
            add("prompt-budget-by-fragment")
            add(this@ChapterCapabilityActivationV1.promptBudgetByFragment.size.toString())
            this@ChapterCapabilityActivationV1.promptBudgetByFragment.forEach { (id, chars) ->
                add(id)
                add(chars.toString())
            }
            add("activation-reason-codes")
            add(this@ChapterCapabilityActivationV1.activationReasonCodes.size.toString())
            addAll(this@ChapterCapabilityActivationV1.activationReasonCodes)
        },
    )

    init {
        require(SHA_256.matches(sourceManifestHash) && SHA_256.matches(requestBindingHash)) {
            "Capability activation source binding is invalid."
        }
    }

    override fun toString(): String =
        "ChapterCapabilityActivationV1(activeCapabilityIds=$activeCapabilityIds, " +
            "requiredPolicyFragmentIds=$requiredPolicyFragmentIds, activationHash=redacted)"
}

class ChapterPromptPolicySelectionV1 internal constructor(
    val binding: PromptBundlePolicyBindingV1,
    val activation: ChapterCapabilityActivationV1,
    private val creativeIntent: OpenCreativeIntentV1,
    instructions: List<PolicyInstructionV1>,
) {
    private val instructions: List<PolicyInstructionV1> = immutableList(instructions)

    fun <T> withPromptContent(block: (creativeIntent: String, instructions: List<PolicyInstructionV1>) -> T): T =
        creativeIntent.withPromptText { text -> block(text, instructions) }

    override fun toString(): String =
        "ChapterPromptPolicySelectionV1(binding=$binding, activation=$activation, " +
            "instructionCount=${instructions.size}, content=redacted)"
}

sealed interface ChapterCapabilityRoutingDecisionV1 {
    class Ready internal constructor(
        val selection: ChapterPromptPolicySelectionV1,
    ) : ChapterCapabilityRoutingDecisionV1

    data class Blocked(
        val reason: ChapterCapabilityBlockReason,
        val capabilityId: String? = null,
    ) : ChapterCapabilityRoutingDecisionV1
}

object ChapterCapabilityRouterV1 {
    fun activate(
        book: BookCapabilityRoutingResultV1,
        request: ChapterCapabilityRequestV1,
    ): ChapterCapabilityRoutingDecisionV1 {
        request.explicitlyRequiredCapabilityIds.firstOrNull {
            BuiltInStateCapabilityCatalogV1.find(it) == null
        }?.let { return ChapterCapabilityRoutingDecisionV1.Blocked(
            ChapterCapabilityBlockReason.UNKNOWN_REQUIRED_ADAPTER,
            it,
        ) }
        request.explicitlyRequiredCapabilityIds.firstOrNull {
            it !in book.manifest.capabilityIds
        }?.let { return ChapterCapabilityRoutingDecisionV1.Blocked(
            ChapterCapabilityBlockReason.REQUIRED_ADAPTER_NOT_IN_MANIFEST,
            it,
        ) }

        val baselineIds = BuiltInStateCapabilityCatalogV1.adapters
            .filter(CapabilityAdapterV1::alwaysActive)
            .mapTo(linkedSetOf(), CapabilityAdapterV1::capabilityId)
        val chapterSignals = BuiltInStateCapabilityCatalogV1.infer(request.inferenceText())
        val active = linkedSetOf<String>().apply {
            addAll(baselineIds)
            addAll(chapterSignals.intersect(book.manifest.capabilityIds))
            addAll(request.explicitlyRequiredCapabilityIds)
        }

        if (request.intimacyRelevant) {
            val capabilityId = "intimacy-continuity"
            if (capabilityId !in book.manifest.capabilityIds) {
                return ChapterCapabilityRoutingDecisionV1.Blocked(
                    ChapterCapabilityBlockReason.REQUIRED_ADAPTER_NOT_IN_MANIFEST,
                    capabilityId,
                )
            }
            when (request.adultGate) {
                RelevantCharacterAdultGate.UNKNOWN -> return ChapterCapabilityRoutingDecisionV1.Blocked(
                    ChapterCapabilityBlockReason.ADULT_STATUS_UNKNOWN,
                    capabilityId,
                )
                RelevantCharacterAdultGate.NOT_CONFIRMED -> return ChapterCapabilityRoutingDecisionV1.Blocked(
                    ChapterCapabilityBlockReason.ADULT_STATUS_NOT_CONFIRMED,
                    capabilityId,
                )
                RelevantCharacterAdultGate.CONFIRMED_ADULTS -> active += capabilityId
            }
        }

        val adapters = active.map { requireNotNull(BuiltInStateCapabilityCatalogV1.find(it)) }
        val fragmentIds = adapters.flatMap(CapabilityAdapterV1::requiredPolicyFragmentIds).toSortedSet()
        val fragmentById = BuiltInWritingPolicyPackV1.value.fragments
            .associateBy(WritingPolicyFragmentV1::fragmentId)
        val selectedFragments = fragmentIds.map { fragmentId ->
            requireNotNull(fragmentById[fragmentId]) {
                "Built-in capability references an unknown policy fragment: $fragmentId"
            }
        }.filter { request.phase in it.applicableStages }
        val selectedIds = selectedFragments.mapTo(sortedSetOf(), WritingPolicyFragmentV1::fragmentId)
        val budgetByFragment = selectedFragments.associate { fragment ->
            fragment.fragmentId to (fragment.hardRules + fragment.softGuidance).sumOf { it.text.length }
        }
        val totalPromptChars = budgetByFragment.values.sum()
        if (totalPromptChars > request.availablePolicyPromptChars ||
            totalPromptChars > BuiltInWritingPolicyPackV1.value.promptBudgetChars
        ) {
            return ChapterCapabilityRoutingDecisionV1.Blocked(
                ChapterCapabilityBlockReason.POLICY_BUDGET_EXCEEDED,
            )
        }

        val activation = ChapterCapabilityActivationV1(
            sourceManifestHash = book.manifest.manifestHash,
            phase = request.phase,
            requestBindingHash = request.bindingHash(),
            activeCapabilityIds = active,
            requiredPolicyFragmentIds = selectedIds,
            expectedStateNamespaceIds = adapters.flatMap(CapabilityAdapterV1::stateNamespaceIds).toSet(),
            forbiddenTransitions = emptySet(),
            promptBudgetByFragment = budgetByFragment,
            activationReasonCodes = buildSet {
                addAll(baselineIds.map { "baseline:$it" })
                addAll((active - baselineIds).map { "chapter-signal:$it" })
                addAll(request.explicitlyRequiredCapabilityIds.map { "explicit-adapter:$it" })
                if (request.intimacyRelevant) add("scene:intimacy-relevant")
            },
        )
        val binding = WritingPolicyPackPromptBundleAdapterV1.bind(
            pack = BuiltInWritingPolicyPackV1.value,
            selectedFragmentIds = selectedIds,
        )
        return ChapterCapabilityRoutingDecisionV1.Ready(
            ChapterPromptPolicySelectionV1(
                binding = binding,
                activation = activation,
                creativeIntent = book.creativeIntent,
                instructions = selectedFragments.flatMap { it.hardRules + it.softGuidance },
            ),
        )
    }
}

object BuiltInWritingPolicyPackV1 {
    val value: WritingPolicyPackV1 by lazy { WritingPolicyPackV1.create(
        packId = WritingPolicyPackCatalogV1.CORE_PACK_ID,
        version = WritingPolicyPackCatalogV1.CORE_PACK_VERSION,
        locale = "zh-CN",
        fragments = BuiltInStateCapabilityCatalogV1.adapters.map { adapter ->
            WritingPolicyFragmentV1(
                fragmentId = adapter.requiredPolicyFragmentIds.single(),
                layer = if (adapter.capabilityId in PRESENTATION_CAPABILITIES) {
                    PolicyFragmentLayer.PRESENTATION
                } else {
                    PolicyFragmentLayer.CONTINUITY
                },
                applicableStages = POLICY_APPLICABLE_STAGES,
                requiredCapabilities = setOf(adapter.capabilityId),
                forbiddenCapabilities = emptySet(),
                priority = priorityFor(adapter.capabilityId),
                maxPromptChars = 320,
                hardRules = listOf(
                    PolicyInstructionV1(
                        id = "rule.${adapter.capabilityId}.v1",
                        text = POLICY_RULES.getValue(adapter.capabilityId),
                    ),
                ),
            )
        },
        validatorIds = setOf("validator.capability-activation.v1"),
        promptBudgetChars = 4_096,
    ) }

    private fun priorityFor(capabilityId: String): WritingPolicyPriority = when (capabilityId) {
        "core-narrative" -> WritingPolicyPriority.NARRATIVE_OBLIGATION
        "character-continuity" -> WritingPolicyPriority.AUTHORITATIVE_FACT
        "romance" -> WritingPolicyPriority.PRESENTATION_AND_STYLE
        else -> WritingPolicyPriority.STATE_TRANSITION
    }

    private val PRESENTATION_CAPABILITIES = setOf("romance")
    private val POLICY_APPLICABLE_STAGES = setOf(
        GenerationPhase.BUILD_STORY_SEED,
        GenerationPhase.BUILD_BIBLE,
        GenerationPhase.BUILD_MASTER_OUTLINE,
        GenerationPhase.BUILD_ARC_PLAN,
        GenerationPhase.BUILD_CHAPTER_PLAN,
        GenerationPhase.DRAFT_CHAPTER,
        GenerationPhase.EXTRACT_MEMORY,
        GenerationPhase.CHECK_CONSISTENCY,
        GenerationPhase.REVISE_CHAPTER,
        GenerationPhase.UPDATE_FUTURE_PLAN,
    )
    private val POLICY_RULES = mapOf(
        "core-narrative" to "兑现本章目标、冲突、转折、后果和钩子；已建立的关键承诺不得无证据消失。",
        "character-continuity" to "人物身份、动机、知识、位置、身体和情绪必须服从当前权威事实，变化须有本章证据。",
        "relationship-progression" to "关系阶段、承诺、信任和边界只能依据可见互动与后果推进，不得无铺垫跳级或回退。",
        "cultivation" to "境界、功法、资源、代价和突破条件必须按已建立规则变化，不得凭空升级或重复突破。",
        "progression-system" to "等级、积分、任务、奖励、冷却和触发必须先校验前置状态，再以本章证据结算。",
        "item-progression" to "道具归属、位置、耐久、能力和成长必须连续；取得、消耗、损坏或升级均需明确证据。",
        "mystery" to "线索、嫌疑、误导、揭示和读者已知信息必须可追溯，不得提前泄漏或遗忘关键线索。",
        "faction-politics" to "阵营利益、同盟、敌对与公开或秘密信息必须按既有立场和事件证据变化。",
        "intimacy-continuity" to "仅在相关虚构角色已确认成年时执行；过程中的位置、身体、物件、感官变化与剧情余波必须连续。",
        "romance" to "情感目标、误解、选择和关系推进必须来自人物行动与后果，不得用标签替代剧情发展。",
    )
}

private fun parseObject(value: String, label: String): JsonObject = runCatching {
    Json.parseToJsonElement(value).jsonObject
}.getOrElse { throw IllegalArgumentException("$label JSON is invalid.") }

private fun JsonObject.requiredString(key: String): String =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Creation intent string field is missing or invalid: $key")

private fun JsonObject.optionalString(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return runCatching { value.jsonPrimitive.content }.getOrElse {
        throw IllegalArgumentException("Creation intent optional field is invalid: $key")
    }
}

private fun JsonObject.optionalStringObject(key: String): Map<String, String> {
    val value = this[key] ?: return emptyMap()
    val objectValue = runCatching { value.jsonObject }.getOrElse {
        throw IllegalArgumentException("Creation intent object field is invalid: $key")
    }
    return ADVANCED_DETAIL_FIELDS.associateWith { field -> objectValue.optionalString(field).orEmpty() }
}

private fun canonicalHash(domain: String, fields: List<String>): String {
    val canonical = StringBuilder()
    fun field(value: String) {
        canonical.append(value.length).append(':').append(value)
    }
    field(domain)
    field(fields.size.toString())
    fields.forEach(::field)
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(values.toList())

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private val SHA_256 = Regex("[0-9a-f]{64}")
private val CAPABILITY_ID = Regex("[a-z0-9][a-z0-9._-]{0,127}")
private val ADVANCED_DETAIL_FIELDS = listOf(
    "charactersAndRelationships",
    "worldAndBackground",
    "narrativeAndStyle",
    "requiredElements",
    "excludedElements",
)
private val ADVANCED_DETAIL_LABELS = mapOf(
    "charactersAndRelationships" to "人物与关系",
    "worldAndBackground" to "世界与背景",
    "narrativeAndStyle" to "叙事与文风",
    "requiredElements" to "必须包含",
    "excludedElements" to "不得包含",
)
