package app.zhijuan.feature.generation

import app.zhijuan.provider.common.ProviderJsonSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructuredOutputValidatorTest {
    private val validator = StructuredOutputValidator()

    @Test
    fun `accepts exact contract and redacts reusable result strings`() {
        val raw = """{"schemaVersion":1,"title":"不能出现在日志的标题","beats":["起","承"]}"""
        val result = validator.validate(raw.toByteArray(), FixtureContract())

        val valid = assertInstanceOf(StructuredOutputValidationResult.Valid::class.java, result)
        assertEquals(1, valid.output.schemaVersion)
        assertEquals("不能出现在日志的标题", valid.output.withDocument { it["title"]?.let(::stringValue) })
        assertFalse(valid.output.toString().contains("不能出现在日志的标题"))
    }

    @Test
    fun `known old schema migrates but unknown or malformed version fails closed`() {
        val contract = FixtureContract(currentVersion = 2, acceptedVersions = setOf(1, 2))
        val migrated = validator.validate(
            """{"schemaVersion":1,"title":"旧版","beats":[]}""".toByteArray(),
            contract,
        )
        val valid = assertInstanceOf(StructuredOutputValidationResult.Valid::class.java, migrated)
        assertEquals(1, valid.output.migratedFromVersion)
        assertEquals(2, valid.output.schemaVersion)

        assertIssue(
            """{"schemaVersion":3,"title":"未来版","beats":[]}""",
            contract,
            StructuredOutputIssueCode.SCHEMA_VERSION_UNSUPPORTED,
        )
        assertIssue(
            """{"schemaVersion":"2","title":"字符串版本","beats":[]}""",
            contract,
            StructuredOutputIssueCode.SCHEMA_VERSION_TYPE_INVALID,
        )
        assertIssue(
            """{"title":"缺版本","beats":[]}""",
            contract,
            StructuredOutputIssueCode.SCHEMA_VERSION_MISSING,
        )
    }

    @Test
    fun `rejects duplicate keys including equivalent escaped names`() {
        assertIssue(
            """{"schemaVersion":1,"na\u006de":"甲","name":"乙","title":"题","beats":[]}""",
            FixtureContract(),
            StructuredOutputIssueCode.DUPLICATE_KEY,
        )
    }

    @Test
    fun `rejects wrappers trailing data arrays and malformed json without guessing`() {
        val cases = listOf(
            "```json\n{\"schemaVersion\":1}\n```" to StructuredOutputIssueCode.INVALID_JSON,
            "{\"schemaVersion\":1} trailing" to StructuredOutputIssueCode.INVALID_JSON,
            "[{\"schemaVersion\":1}]" to StructuredOutputIssueCode.ROOT_NOT_OBJECT,
            "{\"schemaVersion\":1,}" to StructuredOutputIssueCode.INVALID_JSON,
            "{\"schemaVersion\":01}" to StructuredOutputIssueCode.INVALID_JSON,
        )
        cases.forEach { (raw, code) -> assertIssue(raw, FixtureContract(), code) }
    }

    @Test
    fun `enforces depth node collection string and number bounds before schema validation`() {
        assertIssue(
            """{"schemaVersion":1,"x":{"y":{}}}""",
            FixtureContract(limitsOverride = StructuredOutputLimits(maximumDepth = 2)),
            StructuredOutputIssueCode.DEPTH_LIMIT_EXCEEDED,
        )
        assertIssue(
            """{"schemaVersion":1,"x":[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15]}""",
            FixtureContract(limitsOverride = StructuredOutputLimits(maximumNodes = 16)),
            StructuredOutputIssueCode.NODE_LIMIT_EXCEEDED,
        )
        assertIssue(
            """{"schemaVersion":1,"title":"题"}""",
            FixtureContract(limitsOverride = StructuredOutputLimits(maximumObjectMembers = 1)),
            StructuredOutputIssueCode.OBJECT_MEMBER_LIMIT_EXCEEDED,
        )
        assertIssue(
            """{"schemaVersion":1,"title":"题","beats":[1,2]}""",
            FixtureContract(limitsOverride = StructuredOutputLimits(maximumArrayItems = 1)),
            StructuredOutputIssueCode.ARRAY_ITEM_LIMIT_EXCEEDED,
        )
        assertIssue(
            """{"schemaVersion":1,"title":"${"字".repeat(1_025)}","beats":[]}""",
            FixtureContract(limitsOverride = StructuredOutputLimits(maximumStringCharacters = 1_024)),
            StructuredOutputIssueCode.STRING_LIMIT_EXCEEDED,
        )
        assertIssue(
            """{"schemaVersion":1,"title":"题","beats":[],"n":123456789}""",
            FixtureContract(limitsOverride = StructuredOutputLimits(maximumNumberCharacters = 8)),
            StructuredOutputIssueCode.NUMBER_LIMIT_EXCEEDED,
        )
    }

    @Test
    fun `empty invalid utf8 and oversized sources never qualify for repair`() {
        val empty = invalid(validator.validate(ByteArray(0), FixtureContract()))
        assertEquals(StructuredOutputIssueCode.EMPTY_OUTPUT, empty.issues.single().code)
        assertFalse(empty.repairEligible)

        val badUtf8 = invalid(validator.validate(byteArrayOf(0xC3.toByte(), 0x28), FixtureContract()))
        assertEquals(StructuredOutputIssueCode.INVALID_UTF8, badUtf8.issues.single().code)
        assertFalse(badUtf8.repairEligible)

        val limits = StructuredOutputLimits(maximumBytes = 1_024, maximumRepairSourceBytes = 1_024)
        val oversized = invalid(validator.validate(ByteArray(1_025) { 'x'.code.toByte() }, FixtureContract(limitsOverride = limits)))
        assertEquals(StructuredOutputIssueCode.BYTE_LIMIT_EXCEEDED, oversized.issues.single().code)
        assertFalse(oversized.repairEligible)
    }

    @Test
    fun `bounded malformed json can repair while semantic issues reveal no field values`() {
        val malformed = invalid(
            validator.validate(
                """{"schemaVersion":1,"title":"待修复","beats":[}""".toByteArray(),
                FixtureContract(),
            ),
        )
        assertTrue(malformed.repairEligible)
        assertFalse(malformed.toString().contains("待修复"))

        val semantic = invalid(
            validator.validate(
                """{"schemaVersion":1,"title":9,"beats":[],"extra":"私密值"}""".toByteArray(),
                FixtureContract(),
            ),
        )
        assertEquals(
            setOf(StructuredOutputIssueCode.TYPE_MISMATCH, StructuredOutputIssueCode.UNKNOWN_FIELD),
            semantic.issues.map { it.code }.toSet(),
        )
        assertTrue(semantic.repairEligible)
        assertFalse(semantic.toString().contains("私密值"))
    }

    @Test
    fun `issue reporting is deduplicated and capped`() {
        val contract = object : FixtureContract() {
            override fun validate(document: JsonObject): List<StructuredOutputIssue> =
                (0 until 100).map { index ->
                    StructuredOutputIssue(StructuredOutputIssueCode.VALUE_INVALID, "$.items[$index]")
                }
        }
        val report = invalid(
            validator.validate(
                """{"schemaVersion":1,"title":"题","beats":[]}""".toByteArray(),
                contract,
            ),
        )
        assertEquals(64, report.issues.size)
    }

    private fun assertIssue(
        raw: String,
        contract: StructuredOutputContract,
        expected: StructuredOutputIssueCode,
    ) {
        val report = invalid(validator.validate(raw.toByteArray(), contract))
        assertEquals(expected, report.issues.first().code)
    }

    private fun invalid(result: StructuredOutputValidationResult): StructuredOutputInvalidReport =
        assertInstanceOf(StructuredOutputValidationResult.Invalid::class.java, result).report
}

private open class FixtureContract(
    private val currentVersion: Int = 1,
    private val acceptedVersions: Set<Int> = setOf(currentVersion),
    private val limitsOverride: StructuredOutputLimits = StructuredOutputLimits(),
) : StructuredOutputContract {
    override val schemaId: String = "fixture.plan"
    override val currentSchemaVersion: Int = currentVersion
    override val acceptedSchemaVersions: Set<Int> = acceptedVersions
    override val providerSchema: ProviderJsonSchema = ProviderJsonSchema.from(
        """{"type":"object","required":["schemaVersion","title","beats"]}""",
    )
    override val limits: StructuredOutputLimits = limitsOverride

    override fun migrate(sourceVersion: Int, document: JsonObject): JsonObject? =
        if (sourceVersion == 1 && currentSchemaVersion == 2) {
            JsonObject(document + ("schemaVersion" to JsonPrimitive(2)))
        } else {
            null
        }

    override fun validate(document: JsonObject): List<StructuredOutputIssue> = buildList {
        val allowed = setOf("schemaVersion", "title", "beats")
        document.keys.filterNot(allowed::contains).forEach { _ ->
            add(StructuredOutputIssue(StructuredOutputIssueCode.UNKNOWN_FIELD, "$.*"))
        }
        val title = document["title"]
        if (title == null) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$.title"))
        } else if (title !is JsonPrimitive || !title.isString) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$.title"))
        }
        val beats = document["beats"]
        if (beats == null) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.REQUIRED_FIELD_MISSING, "$.beats"))
        } else if (beats !is JsonArray) {
            add(StructuredOutputIssue(StructuredOutputIssueCode.TYPE_MISMATCH, "$.beats"))
        }
    }
}

private fun stringValue(value: kotlinx.serialization.json.JsonElement): String =
    (value as JsonPrimitive).content
