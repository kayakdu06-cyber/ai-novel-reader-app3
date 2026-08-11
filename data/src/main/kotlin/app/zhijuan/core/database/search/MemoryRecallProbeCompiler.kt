package app.zhijuan.core.database.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fixed multi-route priority for deterministic recall probes. A probe from a higher-priority
 * route always wins deduplication over the same probe from a lower-priority route.
 */
internal enum class MemoryRecallProbeRouteV1 {
    TARGET_CHAPTER,
    USER_ADDITION,
    TARGET_ARC,
}

/**
 * One single-token SQLite FTS4 MATCH probe. Later phases may accumulate hits across several
 * probes instead of locking an entire phrase behind one implicit-AND MATCH expression.
 */
internal data class MemoryRecallProbeV1(
    val route: MemoryRecallProbeRouteV1,
    val routeOrdinal: Int,
    val matchExpression: String,
) {
    override fun toString(): String =
        "MemoryRecallProbeV1(route=$route, routeOrdinal=$routeOrdinal, matchExpression=redacted)"
}

/** Bounded compiler output. Omission is evidence, not a reason to fail the whole chapter. */
internal data class MemoryRecallProbeCompilationV1(
    val probes: List<MemoryRecallProbeV1>,
    val omittedUniqueProbeCount: Int,
) {
    init {
        require(probes.size <= 128) { "Compiled recall probes exceed the count limit." }
        require(omittedUniqueProbeCount >= 0) { "Omitted recall probe count is invalid." }
    }

    override fun toString(): String =
        "MemoryRecallProbeCompilationV1(probeCount=${probes.size}, " +
            "omittedUniqueProbeCount=$omittedUniqueProbeCount, probes=redacted)"
}

/**
 * Deterministically compiles readable target-chapter, user-addition and target-arc strings into
 * bounded, safe, auditable single-token MATCH probes.
 *
 * Processing order is fixed: target chapter title, target chapter plan JSON, user addition,
 * target arc title, target arc plan JSON. JSON is parsed strictly; only string leaves are read,
 * object keys are visited in lexicographic order and arrays keep their original order. Each
 * string leaf is tokenized independently through [SearchIndexText.matchExpression] and split into
 * one probe per token. Identical probes keep only the highest-priority, earliest occurrence.
 * The 128-probe result reserves the downstream execution allowance for every populated route,
 * then gives remaining capacity to earlier routes. This prevents a verbose chapter plan from
 * silently starving an explicit user addition or the target arc.
 *
 * All inputs are bounded and every violation fails closed with a static message that never echoes
 * source content. An empty probe list is a legal result.
 */
internal object MemoryRecallProbeCompilerV1 {
    private const val MAX_JSON_CHARS = 64 * 1024
    private const val MAX_JSON_DEPTH = 32
    private const val MAX_STRING_LEAF_COUNT = 256
    private const val MAX_STRING_CHARS = 4 * 1024
    private const val MAX_UNIQUE_PROBES = 128
    private const val MAX_PROBE_CHARS = 128
    private val RESERVED_ROUTE_PROBES = mapOf(
        MemoryRecallProbeRouteV1.TARGET_CHAPTER to 32,
        MemoryRecallProbeRouteV1.USER_ADDITION to 16,
        MemoryRecallProbeRouteV1.TARGET_ARC to 16,
    )

    private val strictJson = Json { isLenient = false }
    private val strictJsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun compile(
        targetChapterTitle: String,
        targetChapterPlanJson: String,
        targetArcTitle: String,
        targetArcPlanJson: String,
        userAddition: String?,
    ): List<MemoryRecallProbeV1> = compileWithEvidence(
        targetChapterTitle = targetChapterTitle,
        targetChapterPlanJson = targetChapterPlanJson,
        targetArcTitle = targetArcTitle,
        targetArcPlanJson = targetArcPlanJson,
        userAddition = userAddition,
    ).probes

    fun compileWithEvidence(
        targetChapterTitle: String,
        targetChapterPlanJson: String,
        targetArcTitle: String,
        targetArcPlanJson: String,
        userAddition: String?,
    ): MemoryRecallProbeCompilationV1 {
        val candidates = MemoryRecallProbeRouteV1.entries.associateWith { mutableListOf<String>() }
        val seen = mutableSetOf<String>()
        val stringLeafBudget = StringLeafBudget()

        fun emit(route: MemoryRecallProbeRouteV1, token: String) {
            require(token.length <= MAX_PROBE_CHARS) { "Recall probe token exceeds the size limit." }
            if (!seen.add(token)) return
            candidates.getValue(route).add(token)
        }

        fun addText(route: MemoryRecallProbeRouteV1, text: String) {
            require(text.length <= MAX_STRING_CHARS) { "Recall probe string exceeds the size limit." }
            tokensOf(text).forEach { emit(route, it) }
        }

        fun addJson(route: MemoryRecallProbeRouteV1, json: String) {
            require(json.length <= MAX_JSON_CHARS) { "Recall probe JSON exceeds the size limit." }
            ensureBoundedNesting(json)
            val root = try {
                strictJson.parseToJsonElement(json)
            } catch (_: Exception) {
                throw IllegalArgumentException("Recall probe JSON is invalid.")
            }
            JsonStringWalker(
                consumeLeaf = stringLeafBudget::consume,
                emit = { token -> emit(route, token) },
            ).walk(root, depth = 0)
        }

        addText(MemoryRecallProbeRouteV1.TARGET_CHAPTER, targetChapterTitle)
        addJson(MemoryRecallProbeRouteV1.TARGET_CHAPTER, targetChapterPlanJson)
        if (userAddition != null) {
            addText(MemoryRecallProbeRouteV1.USER_ADDITION, userAddition)
        }
        addText(MemoryRecallProbeRouteV1.TARGET_ARC, targetArcTitle)
        addJson(MemoryRecallProbeRouteV1.TARGET_ARC, targetArcPlanJson)

        val retainedCounts = MemoryRecallProbeRouteV1.entries.associateWith { route ->
            minOf(candidates.getValue(route).size, RESERVED_ROUTE_PROBES.getValue(route))
        }.toMutableMap()
        var remaining = MAX_UNIQUE_PROBES - retainedCounts.values.sum()
        MemoryRecallProbeRouteV1.entries.forEach { route ->
            if (remaining == 0) return@forEach
            val available = candidates.getValue(route).size - retainedCounts.getValue(route)
            val additional = minOf(available, remaining)
            retainedCounts[route] = retainedCounts.getValue(route) + additional
            remaining -= additional
        }
        val retained = MemoryRecallProbeRouteV1.entries.flatMap { route ->
            candidates.getValue(route)
                .take(retainedCounts.getValue(route))
                .mapIndexed { ordinal, token -> MemoryRecallProbeV1(route, ordinal, token) }
        }
        return MemoryRecallProbeCompilationV1(
            probes = retained,
            omittedUniqueProbeCount = seen.size - retained.size,
        )
    }

    /**
     * Iteratively rejects JSON whose nesting would exceed the depth limit before the strict parser
     * ever recurses into it, keeping parsing work bounded on any input up to the JSON size limit.
     */
    private fun ensureBoundedNesting(json: String) {
        var openContainers = 0
        var index = 0
        while (index < json.length) {
            when (json[index]) {
                '"' -> {
                    index += 1
                    while (index < json.length) {
                        when (json[index]) {
                            '\\' -> index += 2
                            '"' -> {
                                index += 1
                                break
                            }
                            else -> index += 1
                        }
                    }
                }
                '{', '[' -> {
                    require(openContainers < MAX_JSON_DEPTH) {
                        "Recall probe JSON exceeds the nesting limit."
                    }
                    openContainers += 1
                    index += 1
                }
                '}', ']' -> {
                    openContainers -= 1
                    index += 1
                }
                else -> index += 1
            }
        }
    }

    /** Splits a readable string into its single-token MATCH probe forms. */
    private fun tokensOf(text: String): List<String> =
        SearchIndexText.matchExpression(text)?.split(' ') ?: emptyList()

    /** Walks a strictly parsed JSON tree and emits one probe per string-leaf token. */
    private class JsonStringWalker(
        private val consumeLeaf: () -> Unit,
        private val emit: (String) -> Unit,
    ) {
        fun walk(element: JsonElement, depth: Int) {
            require(depth <= MAX_JSON_DEPTH) { "Recall probe JSON exceeds the nesting limit." }
            when (element) {
                JsonNull -> Unit
                is JsonPrimitive -> {
                    if (element.isString) {
                        val content = element.content
                        require(content.length <= MAX_STRING_CHARS) {
                            "Recall probe JSON string exceeds the size limit."
                        }
                        consumeLeaf()
                        tokensOf(content).forEach(emit)
                    } else {
                        require(
                            element.content == "true" ||
                                element.content == "false" ||
                                strictJsonNumber.matches(element.content),
                        ) { "Recall probe JSON contains an invalid literal." }
                    }
                }
                is JsonArray -> element.forEach { walk(it, depth + 1) }
                is JsonObject -> element.keys.sorted().forEach { walk(element.getValue(it), depth + 1) }
            }
        }
    }

    private class StringLeafBudget {
        private var used = 0

        fun consume() {
            require(used < MAX_STRING_LEAF_COUNT) {
                "Recall probe JSON exceeds the string count limit."
            }
            used += 1
        }
    }
}
