package app.zhijuan.core.diagnostics

import app.zhijuan.core.model.StandardErrorCode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

internal object DiagnosticEventCodec {
    private val MAGIC = byteArrayOf(0x5a, 0x4a, 0x44, 0x47)
    private const val FORMAT_VERSION = 1
    private const val MAX_EVENTS = 2_048
    const val MAX_ENCODED_BYTES = 2 * 1024 * 1024
    private const val MAX_STRING_BYTES = 256

    fun encode(events: List<DiagnosticEvent>): ByteArray {
        require(events.size <= MAX_EVENTS) { "Too many diagnostic events." }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(events.size)
            events.forEach { event -> writeEvent(data, event) }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_ENCODED_BYTES) { "Diagnostic log exceeds the encoded size limit." }
        }
    }

    fun decode(encoded: ByteArray): List<DiagnosticEvent> {
        require(encoded.size <= MAX_ENCODED_BYTES) { "Diagnostic log exceeds the encoded size limit." }
        return try {
            DataInputStream(ByteArrayInputStream(encoded)).use { data ->
                val magic = ByteArray(MAGIC.size)
                data.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "Diagnostic log magic is invalid." }
                require(data.readInt() == FORMAT_VERSION) { "Diagnostic log version is unsupported." }
                val count = data.readInt()
                require(count in 0..MAX_EVENTS) { "Diagnostic event count is invalid." }
                List(count) { readEvent(data) }.also {
                    require(data.read() == -1) { "Diagnostic log has trailing bytes." }
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Diagnostic log is malformed.", error)
        }
    }

    private fun writeEvent(output: DataOutputStream, event: DiagnosticEvent) {
        writeText(output, event.eventId)
        output.writeLong(event.timestampEpochMillis)
        writeEnum(output, event.severity)
        writeEnum(output, event.category)
        writeEnum(output, event.code)
        writeEnum(output, event.operation)
        writeNullableEnum(output, event.standardErrorCode)
        writeNullableEnum(output, event.protocol)
        writeNullableInt(output, event.httpStatus)
        writeNullableBoolean(output, event.retryable)
        writeNullableLong(output, event.elapsedMillis)
        writeNullableInt(output, event.androidApiLevel)
        output.writeInt(event.correlationHashes.size)
        event.correlationHashes.forEach { (kind, hash) ->
            writeEnum(output, kind)
            writeText(output, hash)
        }
        output.writeInt(event.errorTypes.size)
        event.errorTypes.forEach { writeText(output, it) }
    }

    private fun readEvent(input: DataInputStream): DiagnosticEvent {
        val eventId = readText(input)
        val timestamp = input.readLong()
        val severity = readEnum<DiagnosticSeverity>(input)
        val category = readEnum<DiagnosticCategory>(input)
        val code = readEnum<DiagnosticCode>(input)
        val operation = readEnum<DiagnosticOperation>(input)
        val standardError = readNullableEnum<StandardErrorCode>(input)
        val protocol = readNullableEnum<DiagnosticProtocol>(input)
        val httpStatus = readNullableInt(input)
        val retryable = readNullableBoolean(input)
        val elapsed = readNullableLong(input)
        val androidApiLevel = readNullableInt(input)
        val correlationCount = input.readInt()
        require(correlationCount in 0..DiagnosticCorrelationKind.entries.size)
        val correlations = buildMap {
            repeat(correlationCount) {
                val kind = readEnum<DiagnosticCorrelationKind>(input)
                require(put(kind, readText(input)) == null) { "Duplicate diagnostic correlation." }
            }
        }
        val errorCount = input.readInt()
        require(errorCount in 0..DiagnosticEvent.MAX_ERROR_TYPES)
        val errors = List(errorCount) { readText(input) }
        return DiagnosticEvent(
            eventId = eventId,
            timestampEpochMillis = timestamp,
            severity = severity,
            category = category,
            code = code,
            operation = operation,
            standardErrorCode = standardError,
            protocol = protocol,
            httpStatus = httpStatus,
            retryable = retryable,
            elapsedMillis = elapsed,
            androidApiLevel = androidApiLevel,
            correlationHashes = correlations,
            errorTypes = errors,
        )
    }

    private fun writeText(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Diagnostic field is too large." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_STRING_BYTES) { "Diagnostic field size is invalid." }
        return ByteArray(size).also { input.readFully(it) }.toString(StandardCharsets.UTF_8)
    }

    private fun writeNullableInt(output: DataOutputStream, value: Int?) {
        output.writeBoolean(value != null)
        value?.let { output.writeInt(it) }
    }

    private fun readNullableInt(input: DataInputStream): Int? =
        if (input.readBoolean()) input.readInt() else null

    private fun writeNullableLong(output: DataOutputStream, value: Long?) {
        output.writeBoolean(value != null)
        value?.let { output.writeLong(it) }
    }

    private fun readNullableLong(input: DataInputStream): Long? =
        if (input.readBoolean()) input.readLong() else null

    private fun writeNullableBoolean(output: DataOutputStream, value: Boolean?) {
        output.writeBoolean(value != null)
        value?.let { output.writeBoolean(it) }
    }

    private fun readNullableBoolean(input: DataInputStream): Boolean? =
        if (input.readBoolean()) input.readBoolean() else null

    private fun <T : Enum<T>> writeEnum(output: DataOutputStream, value: T) =
        writeText(output, value.name)

    private fun <T : Enum<T>> writeNullableEnum(output: DataOutputStream, value: T?) {
        output.writeBoolean(value != null)
        value?.let { writeEnum(output, it) }
    }

    private inline fun <reified T : Enum<T>> readEnum(input: DataInputStream): T =
        enumValueOf(readText(input))

    private inline fun <reified T : Enum<T>> readNullableEnum(input: DataInputStream): T? =
        if (input.readBoolean()) readEnum(input) else null
}
