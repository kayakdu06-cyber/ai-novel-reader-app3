package app.zhijuan.core.database.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryRecallProbeCompilerTest {
    @Test
    fun `Chinese phrases become single char or adjacent bigram ASCII tokens without Chinese`() {
        val probes = MemoryRecallProbeCompilerV1.compile(
            targetChapterTitle = "玄铁剑",
            targetChapterPlanJson = "{}",
            targetArcTitle = "玄",
            targetArcPlanJson = "{}",
            userAddition = null,
        )

        assertEquals(
            listOf("g7384x94c1", "g94c1x5251", "c7384"),
            probes.map(MemoryRecallProbeV1::matchExpression),
        )
        probes.forEach { probe ->
            assertTrue(probe.matchExpression.all { it.code < 128 }) {
                "Probe contains non-ASCII text: $probe"
            }
            assertTrue(probe.matchExpression.all { it.isLetterOrDigit() || it == '_' }) {
                "Probe is not a plain token: $probe"
            }
        }
    }

    @Test
    fun `routes keep fixed priority and JSON key order changes nothing`() {
        val planA = """{"zeta":"玄铁","alpha":"铁剑","mid":{"b":"剑","a":"城"}}"""
        val planB = """{"alpha":"铁剑","mid":{"a":"城","b":"剑"},"zeta":"玄铁"}"""
        val arcPlan = """{"place":"黑木崖"}"""

        val first = MemoryRecallProbeCompilerV1.compile(
            targetChapterTitle = "章",
            targetChapterPlanJson = planA,
            targetArcTitle = "弧",
            targetArcPlanJson = arcPlan,
            userAddition = "补充",
        )
        val second = MemoryRecallProbeCompilerV1.compile(
            targetChapterTitle = "章",
            targetChapterPlanJson = planB,
            targetArcTitle = "弧",
            targetArcPlanJson = arcPlan,
            userAddition = "补充",
        )

        assertEquals(first, second)
        assertEquals(
            listOf(
                MemoryRecallProbeRouteV1.TARGET_CHAPTER,
                MemoryRecallProbeRouteV1.TARGET_CHAPTER,
                MemoryRecallProbeRouteV1.TARGET_CHAPTER,
                MemoryRecallProbeRouteV1.TARGET_CHAPTER,
                MemoryRecallProbeRouteV1.TARGET_CHAPTER,
                MemoryRecallProbeRouteV1.USER_ADDITION,
                MemoryRecallProbeRouteV1.TARGET_ARC,
                MemoryRecallProbeRouteV1.TARGET_ARC,
                MemoryRecallProbeRouteV1.TARGET_ARC,
            ),
            first.map(MemoryRecallProbeV1::route),
        )
        assertEquals(
            listOf("c7ae0", "g94c1x5251", "c57ce", "c5251", "g7384x94c1"),
            first
                .filter { it.route == MemoryRecallProbeRouteV1.TARGET_CHAPTER }
                .map(MemoryRecallProbeV1::matchExpression),
        )
        assertEquals(
            SearchIndexText.matchExpression("补充")!!.split(' '),
            first
                .filter { it.route == MemoryRecallProbeRouteV1.USER_ADDITION }
                .map(MemoryRecallProbeV1::matchExpression),
        )
        assertEquals(
            listOf("c5f27") + SearchIndexText.matchExpression("黑木崖")!!.split(' '),
            first
                .filter { it.route == MemoryRecallProbeRouteV1.TARGET_ARC }
                .map(MemoryRecallProbeV1::matchExpression),
        )
        MemoryRecallProbeRouteV1.entries.forEach { route ->
            val ordinals = first.filter { it.route == route }.map(MemoryRecallProbeV1::routeOrdinal)
            assertEquals((0 until ordinals.size).toList(), ordinals, "route $route")
        }
    }

    @Test
    fun `same token across routes keeps the higher priority route and ordinals stay consecutive`() {
        val probes = MemoryRecallProbeCompilerV1.compile(
            targetChapterTitle = "玄铁",
            targetChapterPlanJson = "{}",
            targetArcTitle = "铁",
            targetArcPlanJson = """{"alias":"玄铁剑"}""",
            userAddition = "玄铁 城",
        )

        assertEquals(
            listOf(
                Triple(MemoryRecallProbeRouteV1.TARGET_CHAPTER, 0, "g7384x94c1"),
                Triple(MemoryRecallProbeRouteV1.USER_ADDITION, 0, "c57ce"),
                Triple(MemoryRecallProbeRouteV1.TARGET_ARC, 0, "c94c1"),
                Triple(MemoryRecallProbeRouteV1.TARGET_ARC, 1, "g94c1x5251"),
            ),
            probes.map { Triple(it.route, it.routeOrdinal, it.matchExpression) },
        )
    }

    @Test
    fun `long phrase becomes separate single token probes instead of one implicit AND expression`() {
        val title = "他握着玄铁剑走向城门"
        val probes = MemoryRecallProbeCompilerV1.compile(
            targetChapterTitle = title,
            targetChapterPlanJson = "{}",
            targetArcTitle = "",
            targetArcPlanJson = "{}",
            userAddition = null,
        )

        val wholeExpression = SearchIndexText.matchExpression(title)!!
        assertTrue(wholeExpression.contains(' '))
        assertEquals(wholeExpression.split(' '), probes.map(MemoryRecallProbeV1::matchExpression))
        assertTrue(probes.size > 1)
        probes.forEach { probe -> assertFalse(probe.matchExpression.contains(' ')) }
    }

    @Test
    fun `blank punctuation numeric boolean and null JSON yields an empty probe list`() {
        assertTrue(
            MemoryRecallProbeCompilerV1.compile(
                targetChapterTitle = "  ，。\n",
                targetChapterPlanJson = """{"n": 42, "flag": true, "nothing": null, "text": ""}""",
                targetArcTitle = "",
                targetArcPlanJson = """[[1, 2], [true], [null], {}]""",
                userAddition = "  \t",
            ).isEmpty(),
        )
        assertTrue(
            MemoryRecallProbeCompilerV1.compile(
                targetChapterTitle = "",
                targetChapterPlanJson = "{}",
                targetArcTitle = "",
                targetArcPlanJson = "[]",
                userAddition = null,
            ).isEmpty(),
        )
    }

    @Test
    fun `JSON arrays keep original order and only string leaves become probes`() {
        val probes = MemoryRecallProbeCompilerV1.compile(
            targetChapterTitle = "",
            targetChapterPlanJson = """{"aliases": ["玄铁", "剑"], "meta": {"count": 2, "active": true}}""",
            targetArcTitle = "",
            targetArcPlanJson = "{}",
            userAddition = null,
        )

        assertEquals(
            listOf("g7384x94c1", "c5251"),
            probes.map(MemoryRecallProbeV1::matchExpression),
        )
    }

    @Test
    fun `nesting up to the depth limit is accepted and one level deeper fails`() {
        val atLimit = MemoryRecallProbeCompilerV1.compile(
            targetChapterTitle = "",
            targetChapterPlanJson = "[".repeat(32) + "\"玄\"" + "]".repeat(32),
            targetArcTitle = "",
            targetArcPlanJson = "{}",
            userAddition = null,
        )
        assertEquals(listOf("c7384"), atLimit.map(MemoryRecallProbeV1::matchExpression))

        val overLimit = assertThrows(IllegalArgumentException::class.java) {
            MemoryRecallProbeCompilerV1.compile(
                targetChapterTitle = "",
                targetChapterPlanJson = "[".repeat(33) + "\"玄\"" + "]".repeat(33),
                targetArcTitle = "",
                targetArcPlanJson = "{}",
                userAddition = null,
            )
        }
        assertEquals("Recall probe JSON exceeds the nesting limit.", overLimit.message)
    }

    @Test
    fun `exactly 256 string leaves are accepted`() {
        val probes = MemoryRecallProbeCompilerV1.compile(
            targetChapterTitle = "",
            targetChapterPlanJson = List(256) { "\"玄\"" }.joinToString(",", "[", "]"),
            targetArcTitle = "",
            targetArcPlanJson = "{}",
            userAddition = null,
        )
        assertEquals(listOf("c7384"), probes.map(MemoryRecallProbeV1::matchExpression))
    }

    @Test
    fun `string leaf limit is shared across chapter and arc JSON`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MemoryRecallProbeCompilerV1.compile(
                targetChapterTitle = "",
                targetChapterPlanJson = List(128) { "\"玄\"" }.joinToString(",", "[", "]"),
                targetArcTitle = "",
                targetArcPlanJson = List(129) { "\"铁\"" }.joinToString(",", "[", "]"),
                userAddition = null,
            )
        }

        assertEquals("Recall probe JSON exceeds the string count limit.", error.message)
    }

    @Test
    fun `malformed or oversized inputs fail closed without echoing canary`() {
        val canary = "CANARY_9f3a8c"

        val malformed = assertThrows(IllegalArgumentException::class.java) {
            MemoryRecallProbeCompilerV1.compile("title", """{"broken": [""", "arc", "{}", null)
        }
        assertNoEcho(malformed, "Recall probe JSON is invalid.", canary)

        val oversizedJson = assertThrows(IllegalArgumentException::class.java) {
            MemoryRecallProbeCompilerV1.compile("title", "\"$canary${"a".repeat(65536)}\"", "arc", "{}", null)
        }
        assertNoEcho(oversizedJson, "Recall probe JSON exceeds the size limit.", canary)

        val tooDeep = assertThrows(IllegalArgumentException::class.java) {
            MemoryRecallProbeCompilerV1.compile(
                "title",
                "[".repeat(33) + "\"$canary\"" + "]".repeat(33),
                "arc",
                "{}",
                null,
            )
        }
        assertNoEcho(tooDeep, "Recall probe JSON exceeds the nesting limit.", canary)

        val tooManyLeaves = assertThrows(IllegalArgumentException::class.java) {
            MemoryRecallProbeCompilerV1.compile(
                "title",
                List(257) { "\"$canary\"" }.joinToString(",", "[", "]"),
                "arc",
                "{}",
                null,
            )
        }
        assertNoEcho(tooManyLeaves, "Recall probe JSON exceeds the string count limit.", canary)

        val oversizedLeaf = assertThrows(IllegalArgumentException::class.java) {
            MemoryRecallProbeCompilerV1.compile(
                "title",
                "\"$canary${"a".repeat(4097)}\"",
                "arc",
                "{}",
                null,
            )
        }
        assertNoEcho(oversizedLeaf, "Recall probe JSON string exceeds the size limit.", canary)

        val oversizedPlainString = assertThrows(IllegalArgumentException::class.java) {
            MemoryRecallProbeCompilerV1.compile("$canary${"a".repeat(4097)}", "{}", "arc", "{}", null)
        }
        assertNoEcho(oversizedPlainString, "Recall probe string exceeds the size limit.", canary)

        val oversizedToken = assertThrows(IllegalArgumentException::class.java) {
            MemoryRecallProbeCompilerV1.compile("${"a".repeat(128)}", "{}", "arc", "{}", null)
        }
        assertNoEcho(oversizedToken, "Recall probe token exceeds the size limit.", canary)
    }

    @Test
    fun `single token at the 128 char boundary is accepted`() {
        val probes = MemoryRecallProbeCompilerV1.compile(
            targetChapterTitle = "a".repeat(127),
            targetChapterPlanJson = "{}",
            targetArcTitle = "",
            targetArcPlanJson = "{}",
            userAddition = null,
        )
        assertEquals(listOf("w${"a".repeat(127)}"), probes.map(MemoryRecallProbeV1::matchExpression))
    }

    @Test
    fun `unique probes beyond 128 are bounded with omission evidence`() {
        val atLimit = MemoryRecallProbeCompilerV1.compileWithEvidence(
            targetChapterTitle = "",
            targetChapterPlanJson = "{}",
            targetArcTitle = "",
            targetArcPlanJson = "{}",
            userAddition = (0 until 128).joinToString(" ") { "probe$it" },
        )
        assertEquals(128, atLimit.probes.size)
        assertEquals(0, atLimit.omittedUniqueProbeCount)
        assertEquals((0 until 128).toList(), atLimit.probes.map(MemoryRecallProbeV1::routeOrdinal))

        val overLimit = MemoryRecallProbeCompilerV1.compileWithEvidence(
            targetChapterTitle = "",
            targetChapterPlanJson = "{}",
            targetArcTitle = "",
            targetArcPlanJson = "{}",
            userAddition = (0 until 130).joinToString(" ") { "probe$it" },
        )
        assertEquals(128, overLimit.probes.size)
        assertEquals(2, overLimit.omittedUniqueProbeCount)
        assertEquals(atLimit.probes, overLimit.probes)
        assertFalse(overLimit.toString().contains("wprobe0"))
        assertTrue(overLimit.toString().contains("probes=redacted"))
    }

    @Test
    fun `large chapter input reserves probes for user addition and target arc`() {
        val compilation = MemoryRecallProbeCompilerV1.compileWithEvidence(
            targetChapterTitle = (0 until 150).joinToString(" ") { "target$it" },
            targetChapterPlanJson = "{}",
            targetArcTitle = (0 until 20).joinToString(" ") { "arc$it" },
            targetArcPlanJson = "{}",
            userAddition = (0 until 20).joinToString(" ") { "user$it" },
        )

        assertEquals(128, compilation.probes.size)
        assertEquals(62, compilation.omittedUniqueProbeCount)
        assertEquals(
            mapOf(
                MemoryRecallProbeRouteV1.TARGET_CHAPTER to 96,
                MemoryRecallProbeRouteV1.USER_ADDITION to 16,
                MemoryRecallProbeRouteV1.TARGET_ARC to 16,
            ),
            compilation.probes.groupingBy(MemoryRecallProbeV1::route).eachCount(),
        )
        MemoryRecallProbeRouteV1.entries.forEach { route ->
            val ordinals = compilation.probes
                .filter { it.route == route }
                .map(MemoryRecallProbeV1::routeOrdinal)
            assertEquals(ordinals.indices.toList(), ordinals)
        }
    }

    @Test
    fun `probe toString redacts the match expression and source text`() {
        val probes = MemoryRecallProbeCompilerV1.compile(
            targetChapterTitle = "玄铁剑",
            targetChapterPlanJson = "{}",
            targetArcTitle = "",
            targetArcPlanJson = "{}",
            userAddition = null,
        )

        val rendered = probes.joinToString()
        assertFalse(rendered.contains("g7384x94c1"))
        assertFalse(rendered.contains("g94c1x5251"))
        assertFalse(rendered.contains("玄铁剑"))
        assertTrue(rendered.contains("redacted"))
        assertTrue(rendered.contains("TARGET_CHAPTER"))
    }

    @Test
    fun `probe routes are a stable three value contract`() {
        assertEquals(
            listOf("TARGET_CHAPTER", "USER_ADDITION", "TARGET_ARC"),
            enumValues<MemoryRecallProbeRouteV1>().map { it.name },
        )
    }

    private fun assertNoEcho(
        error: IllegalArgumentException,
        expectedMessage: String,
        canary: String,
    ) {
        assertEquals(expectedMessage, error.message)
        assertFalse(error.message.orEmpty().contains(canary))
    }
}
