package app.zhijuan.core.task

import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterContextBudgetPolicyTest {
    @Test
    fun `required facts remain whole while low-priority memory is omitted deterministically`() {
        val candidates = requiredCandidates(chapterIndex = 2) + listOf(
            candidate("optional-a", ChapterContextKind.RUNTIME_HISTORY, "甲".repeat(1_500), relevance = 900_000),
            candidate("optional-b", ChapterContextKind.OPEN_FORESHADOW, "乙".repeat(1_500), relevance = 100_000),
        )
        val first = requireReady(assemble(candidates, contextLimit = 8_192))
        val second = requireReady(assemble(candidates.reversed(), contextLimit = 8_192))

        assertEquals(first.providerPayloadJson, second.providerPayloadJson)
        assertEquals(first.contentHash, second.contentHash)
        assertTrue(first.providerPayloadJson.contains("不得改变年龄"))
        assertFalse(first.providerPayloadJson.contains("乙".repeat(1_500)))
        assertTrue(first.omittedItemCount >= 1)
        assertTrue(first.estimatedInputTokens <= first.inputBudgetTokens)
    }

    @Test
    fun `required context over budget blocks instead of truncating a hard fact`() {
        val oversized = requiredCandidates(chapterIndex = 2).map { candidate ->
            if (candidate.kind == ChapterContextKind.BIBLE_HARD_FACT) {
                candidate("hard", ChapterContextKind.BIBLE_HARD_FACT, "不可删硬事实".repeat(2_000))
            } else {
                candidate
            }
        }
        val result = assemble(oversized, contextLimit = 8_192)

        result as ChapterContextAssemblyResult.Blocked
        assertEquals(ChapterContextBlockReason.REQUIRED_CONTEXT_EXCEEDS_BUDGET, result.reason)
        assertTrue(requireNotNull(result.requiredEstimatedTokens) > requireNotNull(result.inputBudgetTokens))
    }

    @Test
    fun `unknown model window needs confirmation and then uses conservative limit`() {
        val unconfirmed = ChapterContextBudgetPolicyV1.assemble(
            targetChapterIndex = 1,
            budget = budget(contextLimit = null, confirmed = false),
            candidates = requiredCandidates(chapterIndex = 1),
        ) as ChapterContextAssemblyResult.Blocked
        assertEquals(
            ChapterContextBlockReason.UNKNOWN_CONTEXT_LIMIT_REQUIRES_CONFIRMATION,
            unconfirmed.reason,
        )

        val confirmed = requireReady(
            ChapterContextBudgetPolicyV1.assemble(
                targetChapterIndex = 1,
                budget = budget(contextLimit = null, confirmed = true),
                candidates = requiredCandidates(chapterIndex = 1),
            ),
        )
        assertEquals(ChapterContextBudgetPolicyV1.CONSERVATIVE_UNKNOWN_CONTEXT_LIMIT, confirmed.effectiveContextLimitTokens)
        assertTrue(confirmed.usedConservativeLimit)
    }

    @Test
    fun `chapter after the first cannot omit previous chapter continuity`() {
        val withoutPrevious = requiredCandidates(chapterIndex = 2)
            .filterNot { it.kind == ChapterContextKind.PREVIOUS_CHAPTER_SUMMARY }
        val result = assemble(withoutPrevious, contextLimit = 32_768) as ChapterContextAssemblyResult.Blocked

        assertEquals(ChapterContextBlockReason.REQUIRED_SOURCE_MISSING, result.reason)
        assertEquals(setOf(ChapterContextKind.PREVIOUS_CHAPTER_SUMMARY), result.missingKinds)
    }

    @Test
    fun `utf8 byte upper bound and safety reserve are explicit in the manifest`() {
        val ready = requireReady(
            ChapterContextBudgetPolicyV1.assemble(
                targetChapterIndex = 1,
                budget = budget(contextLimit = 32_768, confirmed = false),
                candidates = requiredCandidates(chapterIndex = 1),
            ),
        )
        assertEquals(3, ChapterContextBudgetPolicyV1.conservativeTokenUpperBound("中"))
        assertEquals(3_277, ready.safetyReserveTokens)
        assertTrue(ready.sourceManifestJson.contains("\"usedConservativeLimit\":false"))
        assertTrue(ready.sourceManifestJson.contains("\"providerPayloadHash\":\"${ready.contentHash}\""))
    }

    private fun assemble(
        candidates: List<ChapterContextCandidate>,
        contextLimit: Int,
    ) = ChapterContextBudgetPolicyV1.assemble(
        targetChapterIndex = 2,
        budget = budget(contextLimit, confirmed = false),
        candidates = candidates,
    )

    private fun budget(
        contextLimit: Int?,
        confirmed: Boolean,
    ) = ChapterContextBudgetSpec(
        contextLimitTokens = contextLimit,
        maximumOutputTokens = 2_048,
        requestedOutputTokens = 2_048,
        limitSource = if (contextLimit == null) ChapterContextLimitSource.UNKNOWN else ChapterContextLimitSource.OFFICIAL_METADATA,
        unknownLimitConfirmed = confirmed,
        tokenizerFamily = "TEST",
    )

    private fun requiredCandidates(chapterIndex: Int): List<ChapterContextCandidate> = buildList {
        add(candidate("app-rule", ChapterContextKind.APPLICATION_HARD_RULE, "应用硬规则"))
        add(candidate("stage-contract", ChapterContextKind.STAGE_CONTRACT, "只规划当前章"))
        add(candidate("adult", ChapterContextKind.ADULT_AND_IDENTITY_FACT, "人物甲，25岁，已确认成年"))
        add(candidate("world", ChapterContextKind.BIBLE_WORLD_RULE, "世界规则"))
        add(candidate("hard", ChapterContextKind.BIBLE_HARD_FACT, "不得改变年龄"))
        add(candidate("forbidden", ChapterContextKind.FORBIDDEN_CHANGE, "禁止改写已提交事实"))
        add(candidate("arc", ChapterContextKind.TARGET_ARC, "当前故事弧"))
        add(candidate("plan", ChapterContextKind.TARGET_CHAPTER_PLAN, "当前章计划"))
        add(candidate("style", ChapterContextKind.WRITING_STYLE, "叙事风格"))
        if (chapterIndex > 1) {
            add(candidate("previous", ChapterContextKind.PREVIOUS_CHAPTER_SUMMARY, "上一章连续性"))
        }
    }

    private fun candidate(
        id: String,
        kind: ChapterContextKind,
        content: String,
        relevance: Int = 0,
    ) = ChapterContextCandidate(
        itemId = id,
        kind = kind,
        content = content,
        source = ChapterContextSourceRef(
            sourceType = "TEST",
            sourceId = id,
            sourceVersionId = null,
            sourceContentHash = hash(content),
        ),
        relevanceMicros = relevance,
        importance = 50,
    )

    private fun requireReady(result: ChapterContextAssemblyResult) =
        result as ChapterContextAssemblyResult.Ready

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
