package app.zhijuan.feature.generation

import app.zhijuan.provider.common.ProviderJsonSchema
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class StructuredOutputLimits(
    val maximumBytes: Int = 512 * 1_024,
    val maximumRepairSourceBytes: Int = 256 * 1_024,
    val maximumDepth: Int = 32,
    val maximumNodes: Int = 50_000,
    val maximumObjectMembers: Int = 512,
    val maximumArrayItems: Int = 10_000,
    val maximumStringCharacters: Int = 256 * 1_024,
    val maximumNumberCharacters: Int = 128,
) {
    init {
        require(maximumBytes in 1_024..4 * 1_024 * 1_024)
        require(maximumRepairSourceBytes in 1_024..maximumBytes)
        require(maximumDepth in 2..128)
        require(maximumNodes in 16..500_000)
        require(maximumObjectMembers in 1..10_000)
        require(maximumArrayItems in 1..100_000)
        require(maximumStringCharacters in 1_024..4 * 1_024 * 1_024)
        require(maximumNumberCharacters in 8..1_024)
    }
}

enum class StructuredOutputIssueCode {
    EMPTY_OUTPUT,
    BYTE_LIMIT_EXCEEDED,
    INVALID_UTF8,
    INVALID_JSON,
    DUPLICATE_KEY,
    DEPTH_LIMIT_EXCEEDED,
    NODE_LIMIT_EXCEEDED,
    OBJECT_MEMBER_LIMIT_EXCEEDED,
    ARRAY_ITEM_LIMIT_EXCEEDED,
    STRING_LIMIT_EXCEEDED,
    NUMBER_LIMIT_EXCEEDED,
    ROOT_NOT_OBJECT,
    SCHEMA_VERSION_MISSING,
    SCHEMA_VERSION_TYPE_INVALID,
    SCHEMA_VERSION_UNSUPPORTED,
    MIGRATION_FAILED,
    REQUIRED_FIELD_MISSING,
    UNKNOWN_FIELD,
    TYPE_MISMATCH,
    VALUE_INVALID,
}

data class StructuredOutputIssue(
    val code: StructuredOutputIssueCode,
    val path: String = "$",
) {
    init {
        require(path.startsWith('$') && path.length <= 512 && path.none(Char::isISOControl)) {
            "Structured output issue path is invalid."
        }
    }
}

data class StructuredOutputInvalidReport(
    val issues: List<StructuredOutputIssue>,
    val sourceBytes: Int,
    val repairEligible: Boolean,
) {
    init {
        require(issues.isNotEmpty() && issues.size <= MAXIMUM_REPORTED_ISSUES)
        require(sourceBytes >= 0)
    }

    override fun toString(): String =
        "StructuredOutputInvalidReport(issueCodes=${issues.map { it.code }.distinct()}, " +
            "sourceBytes=$sourceBytes, repairEligible=$repairEligible, content=redacted)"

    companion object {
        const val MAXIMUM_REPORTED_ISSUES = 64
    }
}

class ValidatedStructuredOutput internal constructor(
    val schemaId: String,
    val schemaVersion: Int,
    val migratedFromVersion: Int?,
    private val document: JsonObject,
) {
    fun <T> withDocument(block: (JsonObject) -> T): T = block(document)

    override fun toString(): String =
        "ValidatedStructuredOutput(schemaId=$schemaId, schemaVersion=$schemaVersion, " +
            "migrated=${migratedFromVersion != null}, content=redacted)"
}

sealed interface StructuredOutputValidationResult {
    data class Valid(val output: ValidatedStructuredOutput) : StructuredOutputValidationResult
    data class Invalid(val report: StructuredOutputInvalidReport) : StructuredOutputValidationResult
}

interface StructuredOutputContract {
    val schemaId: String
    val currentSchemaVersion: Int
    val acceptedSchemaVersions: Set<Int>
        get() = setOf(currentSchemaVersion)
    val providerSchema: ProviderJsonSchema
    val limits: StructuredOutputLimits
        get() = StructuredOutputLimits()

    fun migrate(sourceVersion: Int, document: JsonObject): JsonObject? = null

    fun validate(document: JsonObject): List<StructuredOutputIssue>
}

class StructuredOutputValidator {
    fun validate(
        source: ByteArray,
        contract: StructuredOutputContract,
    ): StructuredOutputValidationResult {
        validateContract(contract)
        if (source.isEmpty()) return invalid(contract, source.size, StructuredOutputIssueCode.EMPTY_OUTPUT)
        if (source.size > contract.limits.maximumBytes) {
            return invalid(contract, source.size, StructuredOutputIssueCode.BYTE_LIMIT_EXCEEDED)
        }
        val text = decodeUtf8(source)
            ?: return invalid(contract, source.size, StructuredOutputIssueCode.INVALID_UTF8)
        StrictJsonBoundsScanner(text, contract.limits).scan()?.let { code ->
            return invalid(contract, source.size, code)
        }
        val parsed = runCatching { STRICT_JSON.parseToJsonElement(text) }.getOrNull()
            ?: return invalid(contract, source.size, StructuredOutputIssueCode.INVALID_JSON)
        if (parsed !is JsonObject) {
            return invalid(contract, source.size, StructuredOutputIssueCode.ROOT_NOT_OBJECT)
        }
        val versionElement = parsed[SCHEMA_VERSION_FIELD]
            ?: return invalid(
                contract,
                source.size,
                StructuredOutputIssueCode.SCHEMA_VERSION_MISSING,
                "$.schemaVersion",
            )
        val sourceVersion = (versionElement as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.intOrNull
            ?: return invalid(
                contract,
                source.size,
                StructuredOutputIssueCode.SCHEMA_VERSION_TYPE_INVALID,
                "$.schemaVersion",
            )
        if (sourceVersion !in contract.acceptedSchemaVersions) {
            return invalid(
                contract,
                source.size,
                StructuredOutputIssueCode.SCHEMA_VERSION_UNSUPPORTED,
                "$.schemaVersion",
            )
        }
        val migrated = if (sourceVersion == contract.currentSchemaVersion) {
            parsed
        } else {
            contract.migrate(sourceVersion, parsed)
                ?: return invalid(
                    contract,
                    source.size,
                    StructuredOutputIssueCode.MIGRATION_FAILED,
                    "$.schemaVersion",
                )
        }
        val migratedVersion = (migrated[SCHEMA_VERSION_FIELD] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.intOrNull
        if (migratedVersion != contract.currentSchemaVersion) {
            return invalid(
                contract,
                source.size,
                StructuredOutputIssueCode.MIGRATION_FAILED,
                "$.schemaVersion",
            )
        }
        val issues = contract.validate(migrated)
            .distinct()
            .take(StructuredOutputInvalidReport.MAXIMUM_REPORTED_ISSUES)
        if (issues.isNotEmpty()) return invalid(contract, source.size, issues)
        return StructuredOutputValidationResult.Valid(
            ValidatedStructuredOutput(
                schemaId = contract.schemaId,
                schemaVersion = contract.currentSchemaVersion,
                migratedFromVersion = sourceVersion.takeIf { it != contract.currentSchemaVersion },
                document = migrated,
            ),
        )
    }

    private fun invalid(
        contract: StructuredOutputContract,
        sourceBytes: Int,
        code: StructuredOutputIssueCode,
        path: String = "$",
    ): StructuredOutputValidationResult.Invalid = invalid(
        contract,
        sourceBytes,
        listOf(StructuredOutputIssue(code, path)),
    )

    private fun invalid(
        contract: StructuredOutputContract,
        sourceBytes: Int,
        issues: List<StructuredOutputIssue>,
    ): StructuredOutputValidationResult.Invalid {
        val repairEligible = sourceBytes in 1..contract.limits.maximumRepairSourceBytes &&
            issues.none { it.code in NON_REPAIRABLE_ISSUES }
        return StructuredOutputValidationResult.Invalid(
            StructuredOutputInvalidReport(
                issues = issues,
                sourceBytes = sourceBytes,
                repairEligible = repairEligible,
            ),
        )
    }

    private fun validateContract(contract: StructuredOutputContract) {
        require(contract.schemaId.matches(SCHEMA_ID_PATTERN)) { "Structured output schema id is invalid." }
        require(contract.currentSchemaVersion > 0)
        require(
            contract.acceptedSchemaVersions.isNotEmpty() &&
                contract.currentSchemaVersion in contract.acceptedSchemaVersions &&
                contract.acceptedSchemaVersions.size <= 8 &&
                contract.acceptedSchemaVersions.all { it > 0 },
        ) { "Structured output accepted schema versions are invalid." }
    }

    private fun decodeUtf8(source: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(source))
            .toString()
    }.getOrNull()

    private companion object {
        const val SCHEMA_VERSION_FIELD = "schemaVersion"
        val SCHEMA_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val STRICT_JSON = Json {
            isLenient = false
            allowSpecialFloatingPointValues = false
        }
        val NON_REPAIRABLE_ISSUES = setOf(
            StructuredOutputIssueCode.EMPTY_OUTPUT,
            StructuredOutputIssueCode.BYTE_LIMIT_EXCEEDED,
            StructuredOutputIssueCode.INVALID_UTF8,
            StructuredOutputIssueCode.DEPTH_LIMIT_EXCEEDED,
            StructuredOutputIssueCode.NODE_LIMIT_EXCEEDED,
            StructuredOutputIssueCode.OBJECT_MEMBER_LIMIT_EXCEEDED,
            StructuredOutputIssueCode.ARRAY_ITEM_LIMIT_EXCEEDED,
            StructuredOutputIssueCode.STRING_LIMIT_EXCEEDED,
            StructuredOutputIssueCode.NUMBER_LIMIT_EXCEEDED,
        )
    }
}

private class StrictJsonBoundsScanner(
    private val text: String,
    private val limits: StructuredOutputLimits,
) {
    private var cursor = 0
    private var nodes = 0
    private var stringCharacters = 0

    fun scan(): StructuredOutputIssueCode? = try {
        skipWhitespace()
        parseValue(depth = 1)
        skipWhitespace()
        if (cursor != text.length) fail(StructuredOutputIssueCode.INVALID_JSON)
        null
    } catch (failure: ScanFailure) {
        failure.code
    }

    private fun parseValue(depth: Int) {
        if (depth > limits.maximumDepth) fail(StructuredOutputIssueCode.DEPTH_LIMIT_EXCEEDED)
        nodes += 1
        if (nodes > limits.maximumNodes) fail(StructuredOutputIssueCode.NODE_LIMIT_EXCEEDED)
        when (peek()) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> parseString(returnDecoded = false)
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> fail(StructuredOutputIssueCode.INVALID_JSON)
        }
    }

    private fun parseObject(depth: Int) {
        expect('{')
        skipWhitespace()
        if (consumeIf('}')) return
        val keys = HashSet<String>()
        var members = 0
        while (true) {
            if (peek() != '"') fail(StructuredOutputIssueCode.INVALID_JSON)
            val key = parseString(returnDecoded = true)
            if (!keys.add(requireNotNull(key))) fail(StructuredOutputIssueCode.DUPLICATE_KEY)
            members += 1
            if (members > limits.maximumObjectMembers) {
                fail(StructuredOutputIssueCode.OBJECT_MEMBER_LIMIT_EXCEEDED)
            }
            skipWhitespace()
            expect(':')
            skipWhitespace()
            parseValue(depth + 1)
            skipWhitespace()
            if (consumeIf('}')) return
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseArray(depth: Int) {
        expect('[')
        skipWhitespace()
        if (consumeIf(']')) return
        var items = 0
        while (true) {
            items += 1
            if (items > limits.maximumArrayItems) fail(StructuredOutputIssueCode.ARRAY_ITEM_LIMIT_EXCEEDED)
            parseValue(depth + 1)
            skipWhitespace()
            if (consumeIf(']')) return
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseString(returnDecoded: Boolean): String? {
        expect('"')
        val decoded = if (returnDecoded) StringBuilder() else null
        while (cursor < text.length) {
            val char = text[cursor++]
            when {
                char == '"' -> return decoded?.toString()
                char == '\\' -> parseEscape(decoded)
                char.code < 0x20 -> fail(StructuredOutputIssueCode.INVALID_JSON)
                char.isHighSurrogate() -> {
                    if (cursor >= text.length || !text[cursor].isLowSurrogate()) {
                        fail(StructuredOutputIssueCode.INVALID_JSON)
                    }
                    val low = text[cursor++]
                    decoded?.append(char)?.append(low)
                    addStringCharacters(2)
                }
                char.isLowSurrogate() -> fail(StructuredOutputIssueCode.INVALID_JSON)
                else -> {
                    decoded?.append(char)
                    addStringCharacters(1)
                }
            }
        }
        fail(StructuredOutputIssueCode.INVALID_JSON)
    }

    private fun parseEscape(decoded: StringBuilder?) {
        if (cursor >= text.length) fail(StructuredOutputIssueCode.INVALID_JSON)
        when (val escaped = text[cursor++]) {
            '"', '\\', '/' -> {
                decoded?.append(escaped)
                addStringCharacters(1)
            }
            'b' -> appendEscaped(decoded, '\b')
            'f' -> appendEscaped(decoded, '\u000C')
            'n' -> appendEscaped(decoded, '\n')
            'r' -> appendEscaped(decoded, '\r')
            't' -> appendEscaped(decoded, '\t')
            'u' -> {
                val first = parseHexCodeUnit()
                when {
                    first.isHighSurrogate() -> {
                        if (cursor + 2 > text.length || text[cursor] != '\\' || text[cursor + 1] != 'u') {
                            fail(StructuredOutputIssueCode.INVALID_JSON)
                        }
                        cursor += 2
                        val second = parseHexCodeUnit()
                        if (!second.isLowSurrogate()) fail(StructuredOutputIssueCode.INVALID_JSON)
                        decoded?.append(first)?.append(second)
                        addStringCharacters(2)
                    }
                    first.isLowSurrogate() -> fail(StructuredOutputIssueCode.INVALID_JSON)
                    else -> {
                        decoded?.append(first)
                        addStringCharacters(1)
                    }
                }
            }
            else -> fail(StructuredOutputIssueCode.INVALID_JSON)
        }
    }

    private fun appendEscaped(decoded: StringBuilder?, value: Char) {
        decoded?.append(value)
        addStringCharacters(1)
    }

    private fun parseHexCodeUnit(): Char {
        if (cursor + 4 > text.length) fail(StructuredOutputIssueCode.INVALID_JSON)
        var value = 0
        repeat(4) {
            val digit = text[cursor++].digitToIntOrNull(16)
                ?: fail(StructuredOutputIssueCode.INVALID_JSON)
            value = value * 16 + digit
        }
        return value.toChar()
    }

    private fun parseNumber() {
        val start = cursor
        consumeIf('-')
        when (peek()) {
            '0' -> cursor += 1
            in '1'..'9' -> consumeDigits()
            else -> fail(StructuredOutputIssueCode.INVALID_JSON)
        }
        if (consumeIf('.')) {
            if (peek() !in '0'..'9') fail(StructuredOutputIssueCode.INVALID_JSON)
            consumeDigits()
        }
        if (peek() == 'e' || peek() == 'E') {
            cursor += 1
            if (peek() == '+' || peek() == '-') cursor += 1
            if (peek() !in '0'..'9') fail(StructuredOutputIssueCode.INVALID_JSON)
            consumeDigits()
        }
        if (cursor - start > limits.maximumNumberCharacters) {
            fail(StructuredOutputIssueCode.NUMBER_LIMIT_EXCEEDED)
        }
    }

    private fun consumeDigits() {
        while (peek() in '0'..'9') cursor += 1
    }

    private fun parseLiteral(expected: String) {
        if (!text.regionMatches(cursor, expected, 0, expected.length)) {
            fail(StructuredOutputIssueCode.INVALID_JSON)
        }
        cursor += expected.length
    }

    private fun addStringCharacters(count: Int) {
        stringCharacters = Math.addExact(stringCharacters, count)
        if (stringCharacters > limits.maximumStringCharacters) {
            fail(StructuredOutputIssueCode.STRING_LIMIT_EXCEEDED)
        }
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') cursor += 1
    }

    private fun expect(expected: Char) {
        if (!consumeIf(expected)) fail(StructuredOutputIssueCode.INVALID_JSON)
    }

    private fun consumeIf(expected: Char): Boolean {
        if (peek() != expected) return false
        cursor += 1
        return true
    }

    private fun peek(): Char = text.getOrNull(cursor) ?: '\u0000'

    private fun fail(code: StructuredOutputIssueCode): Nothing = throw ScanFailure(code)

    private class ScanFailure(val code: StructuredOutputIssueCode) : RuntimeException(null, null, false, false)
}
