package app.zhijuan.feature.generation

import app.zhijuan.core.model.AdultStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InitialPlanningStructuredOutputTest {
    private val parser = InitialPlanningOutputParser()

    @Test
    fun schemasMatchTheFrozenPromptBundleIdsAndAreProviderReady() {
        assertEquals("story-seed.v1", StorySeedOutputContractV1.schemaId)
        assertEquals("story-bible.v1", StoryBibleOutputContractV1.schemaId)
        assertEquals("master-outline.v1", MasterOutlineOutputContractV1.schemaId)
        assertTrue(StorySeedOutputContractV1.providerSchema.characterCount > 500)
        assertTrue(StoryBibleOutputContractV1.providerSchema.characterCount > 500)
        assertTrue(MasterOutlineOutputContractV1.providerSchema.characterCount > 500)
        StoryBibleOutputContractV1.providerSchema.withValue { schema ->
            assertTrue("\"ruleId\"" in schema)
            assertTrue("\"factId\"" in schema)
            assertTrue("\"additionalProperties\":false" in schema)
        }
        MasterOutlineOutputContractV1.providerSchema.withValue { schema ->
            assertTrue("\"maximum\":10000" in schema)
            assertTrue("\"startChapter\"" in schema)
        }
    }

    @Test
    fun validAdultPlanningBundlePreservesEightyChapterTargetAndCrossDocumentHashes() {
        val bundle = validBundle(target = 80)
        val valid = assertInstanceOf(InitialPlanningBundleValidationResult.Valid::class.java, bundle)
        assertEquals(80, valid.outline.targetChapterCount)
        assertEquals(AdultStatus.CONFIRMED_ADULT, valid.bible.characters.single().adultStatus)
        assertEquals(22, valid.bible.characters.single().ageYears)
        assertEquals(3, valid.outline.beats.size)
    }

    @Test
    fun mediumAndCustomLongTargetsRemainExactInsteadOfShrinking() {
        listOf(300, 1_200).forEach { target ->
            val valid = assertInstanceOf(
                InitialPlanningBundleValidationResult.Valid::class.java,
                validBundle(target),
            )
            assertEquals(target, valid.seed.targetChapterCount)
            assertEquals(target, valid.outline.targetChapterCount)
            assertEquals(target, valid.outline.beats.last().endChapter)
        }
    }

    @Test
    fun unspecifiedNewFictionalCharacterCanArriveAsAnExplicitAdultFactWithoutAnotherQuestion() {
        val parsed = parsedSeed(validSeed(80))
        assertEquals(22, parsed.characters.single().ageYears)
        assertEquals(AdultStatus.CONFIRMED_ADULT, parsed.characters.single().adultStatus)
        assertFalse(parsed.characters.single().realIdentifiablePerson)
        assertTrue(parsed.characters.single().intimacyRole)
    }

    @Test
    fun explicitMinorAndRealPersonIntimacyRolesAreBlockedAcrossDocuments() {
        val minor = parsedSeed(
            validSeed(80)
                .decodeToString()
                .replace("\"ageYears\":22", "\"ageYears\":17")
                .replace("CONFIRMED_ADULT", "NOT_ADULT")
                .encodeToByteArray(),
        )
        val minorBible = parsedBible(validBible(minor, age = 17, adultStatus = "NOT_ADULT"))
        val minorOutline = parsedOutline(validOutline(minorBible, 80))
        val minorIssues = assertInstanceOf(
            InitialPlanningBundleValidationResult.Invalid::class.java,
            InitialPlanningBundleValidator.validate(minor, minorBible, minorOutline, 80),
        ).issues
        assertTrue(minorIssues.any { it.code == InitialPlanningCrossIssueCode.INTIMACY_ROLE_NOT_CONFIRMED_ADULT })

        val real = parsedSeed(
            validSeed(80).decodeToString()
                .replace("\"realIdentifiablePerson\":false", "\"realIdentifiablePerson\":true")
                .encodeToByteArray(),
        )
        val realBible = parsedBible(validBible(real, realPerson = true))
        val realOutline = parsedOutline(validOutline(realBible, 80))
        val realIssues = assertInstanceOf(
            InitialPlanningBundleValidationResult.Invalid::class.java,
            InitialPlanningBundleValidator.validate(real, realBible, realOutline, 80),
        ).issues
        assertTrue(realIssues.any { it.code == InitialPlanningCrossIssueCode.REAL_PERSON_IN_INTIMACY_ROLE })
    }

    @Test
    fun mismatchedHashesAndCharacterFactsFailBeforePersistence() {
        val seed = parsedSeed(validSeed(80))
        val bible = parsedBible(
            validBible(seed).decodeToString()
                .replace(seed.contentHash, "0".repeat(64))
                .replace("\"ageYears\":22", "\"ageYears\":23")
                .encodeToByteArray(),
        )
        val outline = parsedOutline(validOutline(bible, 80))
        val issues = assertInstanceOf(
            InitialPlanningBundleValidationResult.Invalid::class.java,
            InitialPlanningBundleValidator.validate(seed, bible, outline, 80),
        ).issues
        assertTrue(issues.any { it.code == InitialPlanningCrossIssueCode.SEED_HASH_MISMATCH })
        assertTrue(issues.any { it.code == InitialPlanningCrossIssueCode.CHARACTER_FACT_MISMATCH })
    }

    @Test
    fun gappedMasterOutlineFailsEvenWhenEachBeatIsIndividuallyValid() {
        val seed = parsedSeed(validSeed(80))
        val bible = parsedBible(validBible(seed))
        val outline = parsedOutline(
            validOutline(bible, 80).decodeToString()
                .replace("\"startChapter\":27", "\"startChapter\":28")
                .encodeToByteArray(),
        )
        val issues = assertInstanceOf(
            InitialPlanningBundleValidationResult.Invalid::class.java,
            InitialPlanningBundleValidator.validate(seed, bible, outline, 80),
        ).issues
        assertTrue(issues.any { it.code == InitialPlanningCrossIssueCode.OUTLINE_RANGE_NOT_CONTIGUOUS })
    }

    @Test
    fun strictContractsRejectUnknownFieldsInvalidAdultFactsAndDuplicateKeys() {
        val unknown = validSeed(80).decodeToString()
            .replace("\"openQuestions\":[]", "\"openQuestions\":[],\"extra\":true")
            .encodeToByteArray()
        val unknownReport = assertInstanceOf(
            PlanningOutputValidationResult.Invalid::class.java,
            parser.storySeed(unknown),
        ).report
        assertTrue(unknownReport.issues.any { it.code == StructuredOutputIssueCode.UNKNOWN_FIELD })

        val inconsistentAdult = validSeed(80).decodeToString()
            .replace("\"ageYears\":22", "\"ageYears\":17")
            .encodeToByteArray()
        val adultReport = assertInstanceOf(
            PlanningOutputValidationResult.Invalid::class.java,
            parser.storySeed(inconsistentAdult),
        ).report
        assertTrue(adultReport.issues.any { it.code == StructuredOutputIssueCode.VALUE_INVALID })

        val duplicate = validSeed(80).decodeToString()
            .replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")
            .encodeToByteArray()
        val duplicateReport = assertInstanceOf(
            PlanningOutputValidationResult.Invalid::class.java,
            parser.storySeed(duplicate),
        ).report
        assertTrue(duplicateReport.issues.any { it.code == StructuredOutputIssueCode.DUPLICATE_KEY })
    }

    @Test
    fun diagnosticsRedactPlanningContentAndHashes() {
        val seed = parsedSeed(validSeed(80))
        val bible = parsedBible(validBible(seed))
        val outline = parsedOutline(validOutline(bible, 80))
        listOf(seed.toString(), bible.toString(), outline.toString()).forEach { diagnostic ->
            assertTrue("content=redacted" in diagnostic)
            assertFalse(seed.premise in diagnostic)
            assertFalse(seed.contentHash in diagnostic)
        }
    }

    @Test
    fun characterContractsRejectNotApplicableAdultStatusBeforeDatabaseInsertion() {
        val invalid = validSeed(80).decodeToString()
            .replace("CONFIRMED_ADULT", "NOT_APPLICABLE")
            .replace("\"ageYears\":22", "\"ageYears\":null")
            .encodeToByteArray()
        val report = assertInstanceOf(
            PlanningOutputValidationResult.Invalid::class.java,
            parser.storySeed(invalid),
        ).report
        assertTrue(report.issues.any { it.code == StructuredOutputIssueCode.VALUE_INVALID })
    }

    @Test
    fun validBundleMapsToDeterministicImmutablePersistenceDrafts() {
        val valid = assertInstanceOf(
            InitialPlanningBundleValidationResult.Valid::class.java,
            validBundle(300),
        )
        val ids = InitialPlanningPersistenceIds(
            bookId = "book.mapping",
            bibleRevisionId = "bible.mapping.1",
            outlineRevisionId = "outline.mapping.1",
            seedNextStageId = "stage.bible",
            bibleStageId = "stage.bible",
            bibleNextStageId = "stage.outline",
            outlineStageId = "stage.outline",
        )
        val first = InitialPlanningPersistenceMapper.map(valid, ids, 10L, 20L, 30L)
        val second = InitialPlanningPersistenceMapper.map(valid, ids, 10L, 20L, 30L)
        assertEquals(valid.seed.canonicalJson, first.seed.canonicalJson)
        assertEquals(valid.bible.contentHash, first.bible.revision.contentHash)
        assertEquals(valid.outline.contentHash, first.outline.revision.contentHash)
        assertEquals(first.bible.characters.single().entityId, second.bible.characters.single().entityId)
        assertEquals(2, first.bible.hardFacts.size)
        assertEquals(300, valid.outline.targetChapterCount)
        assertThrows(IllegalArgumentException::class.java) {
            InitialPlanningPersistenceMapper.map(
                valid,
                ids.copy(bibleStageId = "stage.wrong"),
                10L,
                20L,
                30L,
            )
        }
    }

    private fun validBundle(target: Int): InitialPlanningBundleValidationResult {
        val seed = parsedSeed(validSeed(target))
        val bible = parsedBible(validBible(seed))
        val outline = parsedOutline(validOutline(bible, target))
        return InitialPlanningBundleValidator.validate(seed, bible, outline, target)
    }

    private fun parsedSeed(source: ByteArray): StorySeedV1 = when (val result = parser.storySeed(source)) {
        is PlanningOutputValidationResult.Valid -> result.value
        is PlanningOutputValidationResult.Invalid -> error(result.report.toString())
    }

    private fun parsedBible(source: ByteArray): StoryBibleV1 = when (val result = parser.storyBible(source)) {
        is PlanningOutputValidationResult.Valid -> result.value
        is PlanningOutputValidationResult.Invalid -> error(result.report.toString())
    }

    private fun parsedOutline(source: ByteArray): MasterOutlineV1 = when (val result = parser.masterOutline(source)) {
        is PlanningOutputValidationResult.Valid -> result.value
        is PlanningOutputValidationResult.Invalid -> error(result.report.toString())
    }

    private fun validSeed(target: Int): ByteArray = """
        {"schemaVersion":1,"targetChapterCount":$target,"premise":"一名档案修复师发现城市记忆正在被改写。","centralConflict":"她必须在守住身份与揭开真相之间选择。","storyPromise":"以持续升级的谜团检验人物关系和记忆真伪。","endingDirection":"主角公开证据并承担由此改变关系的代价。","characters":[{"entityId":"char.lin","name":"林澈","ageYears":22,"adultStatus":"CONFIRMED_ADULT","realIdentifiablePerson":false,"intimacyRole":true,"storyRole":"主角","desire":"找回被删除的家庭记忆","obstacle":"她自己的证词也可能被篡改"}],"openQuestions":[]}
    """.trimIndent().encodeToByteArray()

    private fun validBible(
        seed: StorySeedV1,
        age: Int = 22,
        adultStatus: String = "CONFIRMED_ADULT",
        realPerson: Boolean = false,
    ): ByteArray = """
        {"schemaVersion":1,"seedContentHash":"${seed.contentHash}","characters":[{"entityId":"char.lin","canonicalName":"林澈","aliases":[],"ageYears":$age,"adultStatus":"$adultStatus","realIdentifiablePerson":$realPerson,"storyRole":"主角与档案修复师","stableTraits":["谨慎","重视可验证证据"],"goals":["找回家庭记忆"],"boundaries":["不伤害无关者"]}],"worldRules":[{"ruleId":"rule.memory","text":"被改写的记忆会留下可校验的纸质痕迹。"}],"hardFacts":[{"factId":"fact.job","entityId":"char.lin","text":"林澈以修复历史档案为职业。"}],"themes":["记忆与身份"],"writingStyle":["有限视角","线索递进"],"forbiddenChanges":["不得把记忆改写解释为无条件梦境"]}
    """.trimIndent().encodeToByteArray()

    private fun validOutline(bible: StoryBibleV1, target: Int): ByteArray {
        val firstEnd = target / 3
        val secondStart = firstEnd + 1
        val secondEnd = target * 2 / 3
        val thirdStart = secondEnd + 1
        return """
            {"schemaVersion":1,"bibleContentHash":"${bible.contentHash}","targetChapterCount":$target,"title":"纸上余温","endingPromise":"最后的证据公开，但人物必须承担真实关系的代价。","beats":[{"beatId":"beat.open","title":"裂缝出现","startChapter":1,"endChapter":$firstEnd,"goal":"建立异常与主角目标","turningPoint":"主角发现自己的签名出现在不存在的档案上","outcome":"她决定秘密调查"},{"beatId":"beat.pressure","title":"证词反噬","startChapter":$secondStart,"endChapter":$secondEnd,"goal":"扩大对手压力与关系成本","turningPoint":"最可信的同伴给出互相矛盾的记忆","outcome":"主角失去原有安全位置"},{"beatId":"beat.resolve","title":"公开代价","startChapter":$thirdStart,"endChapter":$target,"goal":"完成证据链并兑现人物选择","turningPoint":"主角确认自己也参与过早期实验","outcome":"她公开真相并接受关系重建"}]}
        """.trimIndent().encodeToByteArray()
    }
}
