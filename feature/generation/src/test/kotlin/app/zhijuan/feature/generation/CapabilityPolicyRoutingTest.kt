package app.zhijuan.feature.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.RelevantCharacterAdultGate
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapabilityPolicyRoutingTest {
    @Test
    fun `unlisted free subject reaches prompt without unrelated adapter overhead`() {
        val idea = "钟表修复师每修好一只旧钟，就会听见一段无人记得的未来与回声契约。"
        val book = derive(idea)
        val decision = ChapterCapabilityRouterV1.activate(
            book,
            request(chapterTask = "修复师第一次验证听见的未来是否会发生"),
        ) as ChapterCapabilityRoutingDecisionV1.Ready

        assertEquals(
            setOf("character-continuity", "core-narrative"),
            decision.selection.activation.activeCapabilityIds,
        )
        assertFalse(decision.selection.activation.expectedStateNamespaceIds.contains("system"))
        assertFalse(decision.selection.activation.requiredPolicyFragmentIds.any { "cultivation" in it })
        decision.selection.withPromptContent { creativeIntent, instructions ->
            assertTrue(creativeIntent.contains(idea))
            assertEquals(2, instructions.size)
        }
    }

    @Test
    fun `mixed intent activates only the relevant deterministic adapter set`() {
        val idea = "成年修仙者与道侣共同使用升级系统，以积分强化本命法宝并推进恋爱关系。"
        val firstBook = derive(idea)
        val secondBook = derive(idea)
        val chapterRequest = request(
            chapterTask = "主角修炼突破境界，完成系统任务，消耗积分升级法宝，并向道侣兑现承诺",
        )
        val first = ChapterCapabilityRouterV1.activate(firstBook, chapterRequest)
            as ChapterCapabilityRoutingDecisionV1.Ready
        val second = ChapterCapabilityRouterV1.activate(secondBook, chapterRequest)
            as ChapterCapabilityRoutingDecisionV1.Ready

        val expected = setOf(
            "core-narrative",
            "character-continuity",
            "relationship-progression",
            "cultivation",
            "progression-system",
            "item-progression",
            "romance",
        )
        assertEquals(expected, first.selection.activation.activeCapabilityIds)
        assertEquals(firstBook.manifest.manifestHash, secondBook.manifest.manifestHash)
        assertEquals(first.selection.activation.activationHash, second.selection.activation.activationHash)
        assertFalse("mystery" in first.selection.activation.activeCapabilityIds)
        assertFalse("intimacy-continuity" in first.selection.activation.activeCapabilityIds)
    }

    @Test
    fun `unknown adult status and explicitly missing adapter fail closed`() {
        val book = derive("两名成年虚构角色的亲密关系会改变结盟结果。")
        val unknownAge = ChapterCapabilityRouterV1.activate(
            book,
            request(
                chapterTask = "两人的亲密选择改变彼此信任",
                intimacyRelevant = true,
                adultGate = RelevantCharacterAdultGate.UNKNOWN,
            ),
        ) as ChapterCapabilityRoutingDecisionV1.Blocked
        val missingAdapter = ChapterCapabilityRouterV1.activate(
            book,
            request(
                chapterTask = "结算灵魂契约",
                explicitlyRequiredCapabilityIds = setOf("soul-contract"),
            ),
        ) as ChapterCapabilityRoutingDecisionV1.Blocked
        val knownButAbsent = ChapterCapabilityRouterV1.activate(
            derive("钟表修复师调查一座城市的时间错位。"),
            request(
                chapterTask = "执行升级任务",
                explicitlyRequiredCapabilityIds = setOf("progression-system"),
            ),
        ) as ChapterCapabilityRoutingDecisionV1.Blocked

        assertEquals(ChapterCapabilityBlockReason.ADULT_STATUS_UNKNOWN, unknownAge.reason)
        assertEquals(ChapterCapabilityBlockReason.UNKNOWN_REQUIRED_ADAPTER, missingAdapter.reason)
        assertEquals("soul-contract", missingAdapter.capabilityId)
        assertEquals(ChapterCapabilityBlockReason.REQUIRED_ADAPTER_NOT_IN_MANIFEST, knownButAbsent.reason)
    }

    private fun derive(storyIdea: String): BookCapabilityRoutingResultV1 {
        val sourceHash = sha256(storyIdea)
        val advanced = """{
            "charactersAndRelationships":"",
            "worldAndBackground":"",
            "narrativeAndStyle":"",
            "requiredElements":"",
            "excludedElements":""
        }""".trimIndent()
        return BookCapabilityRouterV1.derive(
            CreationSnapshotIntentSourceV1(
                sourceContentHash = sourceHash,
                rawInputJson = """{"storyIdea":${quoted(storyIdea)},"requestedGenreId":null,"advancedDetails":$advanced}""",
                normalizedInputJson = """{"storyIdea":${quoted(storyIdea)},"advancedDetails":$advanced}""",
            ),
        )
    }

    private fun request(
        chapterTask: String,
        explicitlyRequiredCapabilityIds: Set<String> = emptySet(),
        intimacyRelevant: Boolean = false,
        adultGate: RelevantCharacterAdultGate = RelevantCharacterAdultGate.CONFIRMED_ADULTS,
    ) = ChapterCapabilityRequestV1(
        phase = GenerationPhase.BUILD_CHAPTER_PLAN,
        chapterTaskText = chapterTask,
        explicitlyRequiredCapabilityIds = explicitlyRequiredCapabilityIds,
        intimacyRelevant = intimacyRelevant,
        adultGate = adultGate,
        availablePolicyPromptChars = 4_096,
    )

    private fun quoted(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
