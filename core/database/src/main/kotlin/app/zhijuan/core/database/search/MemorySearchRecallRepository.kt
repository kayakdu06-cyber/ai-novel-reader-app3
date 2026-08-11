package app.zhijuan.core.database.search

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import java.security.MessageDigest

internal data class MemorySearchRecallHitV1(
    val document: MemorySearchDocumentEntity,
    val targetChapterProbeHits: Int,
    val userAdditionProbeHits: Int,
    val targetArcProbeHits: Int,
) {
    val totalProbeHits: Int =
        targetChapterProbeHits + userAdditionProbeHits + targetArcProbeHits

    init {
        require(targetChapterProbeHits >= 0) { "Target-chapter hit count is invalid." }
        require(userAdditionProbeHits >= 0) { "User-addition hit count is invalid." }
        require(targetArcProbeHits >= 0) { "Target-arc hit count is invalid." }
        require(totalProbeHits > 0) { "A recall hit must have at least one probe hit." }
    }

    override fun toString(): String =
        "MemorySearchRecallHitV1(document=redacted, " +
            "targetChapterProbeHits=$targetChapterProbeHits, " +
            "userAdditionProbeHits=$userAdditionProbeHits, " +
            "targetArcProbeHits=$targetArcProbeHits, totalProbeHits=$totalProbeHits)"
}

internal data class MemorySearchRecallResultV1(
    val hits: List<MemorySearchRecallHitV1>,
    val queryFingerprint: String,
    val compiledProbeCount: Int,
    val omittedCompiledProbeCount: Int,
    val executedProbeCount: Int,
    val executedTargetChapterProbeCount: Int,
    val executedUserAdditionProbeCount: Int,
    val executedTargetArcProbeCount: Int,
    val omittedExecutionProbeCount: Int,
    val omittedRankedDocumentCount: Int,
) {
    init {
        require(hits.size <= 128) { "Recall result exceeds the document limit." }
        require(queryFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Recall query fingerprint is invalid."
        }
        require(compiledProbeCount in 0..128) { "Compiled recall probe count is invalid." }
        require(omittedCompiledProbeCount >= 0) { "Omitted compiled probe count is invalid." }
        require(executedProbeCount in 0..64) { "Executed recall probe count is invalid." }
        require(executedTargetChapterProbeCount in 0..32) {
            "Executed target-chapter probe count is invalid."
        }
        require(executedUserAdditionProbeCount in 0..16) {
            "Executed user-addition probe count is invalid."
        }
        require(executedTargetArcProbeCount in 0..16) {
            "Executed target-arc probe count is invalid."
        }
        require(omittedExecutionProbeCount >= 0) { "Omitted execution probe count is invalid." }
        require(omittedRankedDocumentCount >= 0) { "Omitted recall document count is invalid." }
        require(executedProbeCount + omittedExecutionProbeCount == compiledProbeCount) {
            "Recall probe accounting is inconsistent."
        }
        require(
            executedTargetChapterProbeCount +
                executedUserAdditionProbeCount +
                executedTargetArcProbeCount == executedProbeCount,
        ) { "Recall route accounting is inconsistent." }
    }

    override fun toString(): String =
        "MemorySearchRecallResultV1(hitCount=${hits.size}, queryFingerprint=$queryFingerprint, " +
            "compiledProbeCount=$compiledProbeCount, " +
            "omittedCompiledProbeCount=$omittedCompiledProbeCount, " +
            "executedProbeCount=$executedProbeCount, " +
            "executedTargetChapterProbeCount=$executedTargetChapterProbeCount, " +
            "executedUserAdditionProbeCount=$executedUserAdditionProbeCount, " +
            "executedTargetArcProbeCount=$executedTargetArcProbeCount, " +
            "omittedExecutionProbeCount=$omittedExecutionProbeCount, " +
            "omittedRankedDocumentCount=$omittedRankedDocumentCount, hits=redacted)"
}

/**
 * Executes bounded FTS probes against the encrypted production database and accumulates hits by
 * stable search-document identity. This layer deliberately returns search pointers only; the
 * authoritative source rows are reloaded and validated by the following hydration phase.
 */
internal class MemorySearchRecallRepositoryV1(
    private val database: ZhijuanDatabase,
) {
    suspend fun recall(
        bookId: String,
        targetChapterIndex: Int,
        targetChapterTitle: String,
        targetChapterPlanJson: String,
        targetArcTitle: String,
        targetArcPlanJson: String,
        userAddition: String?,
    ): MemorySearchRecallResultV1 {
        require(bookId.isNotBlank() && bookId.length <= MAX_ID_CHARS) {
            "Recall book identity is invalid."
        }
        require(targetChapterIndex >= 1) { "Recall target chapter is invalid." }

        val compilation = MemoryRecallProbeCompilerV1.compileWithEvidence(
            targetChapterTitle = targetChapterTitle,
            targetChapterPlanJson = targetChapterPlanJson,
            targetArcTitle = targetArcTitle,
            targetArcPlanJson = targetArcPlanJson,
            userAddition = userAddition,
        )
        val executedProbes = selectExecutionProbes(compilation.probes)
        val fingerprint = queryFingerprint(bookId, targetChapterIndex, executedProbes)

        return database.withTransaction {
            requireNotNull(database.libraryDao().findBook(bookId)) {
                "Recall book does not exist."
            }
            val search = database.memorySearchDao()
            val accumulators = linkedMapOf<String, MutableRecallHit>()
            val documentIdBySource = mutableMapOf<SourceIdentity, String>()

            executedProbes.forEach { probe ->
                val rows = search.searchBeforeChapter(
                    bookId = bookId,
                    matchExpression = probe.matchExpression,
                    targetChapterIndex = targetChapterIndex,
                    limit = MAX_DOCUMENTS_PER_PROBE,
                )
                check(rows.size <= MAX_DOCUMENTS_PER_PROBE) {
                    "Recall query exceeded its document limit."
                }
                check(rows.map(MemorySearchDocumentEntity::documentId).distinct().size == rows.size) {
                    "Recall query returned duplicate document identities."
                }
                rows.forEach { document ->
                    validateDocument(document, bookId, targetChapterIndex)
                    val sourceIdentity = SourceIdentity(
                        bookId = document.bookId,
                        sourceType = document.sourceType,
                        sourceId = document.sourceId,
                    )
                    val existingDocumentId = documentIdBySource.putIfAbsent(
                        sourceIdentity,
                        document.documentId,
                    )
                    check(existingDocumentId == null || existingDocumentId == document.documentId) {
                        "Recall source identity maps to multiple documents."
                    }
                    val accumulator = accumulators.getOrPut(document.documentId) {
                        MutableRecallHit(document)
                    }
                    check(accumulator.document == document) {
                        "Recall document changed during accumulation."
                    }
                    accumulator.record(probe.route)
                }
            }

            val ranked = accumulators.values
                .map(MutableRecallHit::toHit)
                .sortedWith(HIT_ORDER)
            val retained = ranked.take(MAX_RETURNED_DOCUMENTS)
            MemorySearchRecallResultV1(
                hits = retained,
                queryFingerprint = fingerprint,
                compiledProbeCount = compilation.probes.size,
                omittedCompiledProbeCount = compilation.omittedUniqueProbeCount,
                executedProbeCount = executedProbes.size,
                executedTargetChapterProbeCount = executedProbes.count {
                    it.route == MemoryRecallProbeRouteV1.TARGET_CHAPTER
                },
                executedUserAdditionProbeCount = executedProbes.count {
                    it.route == MemoryRecallProbeRouteV1.USER_ADDITION
                },
                executedTargetArcProbeCount = executedProbes.count {
                    it.route == MemoryRecallProbeRouteV1.TARGET_ARC
                },
                omittedExecutionProbeCount = compilation.probes.size - executedProbes.size,
                omittedRankedDocumentCount = ranked.size - retained.size,
            )
        }
    }

    private fun selectExecutionProbes(
        probes: List<MemoryRecallProbeV1>,
    ): List<MemoryRecallProbeV1> {
        require(probes.map(MemoryRecallProbeV1::matchExpression).distinct().size == probes.size) {
            "Compiled recall probes contain duplicate expressions."
        }
        MemoryRecallProbeRouteV1.entries.forEach { route ->
            val routeProbes = probes.filter { it.route == route }
            require(routeProbes.map(MemoryRecallProbeV1::routeOrdinal) == routeProbes.indices.toList()) {
                "Compiled recall probe ordinals are invalid."
            }
        }
        probes.forEach { probe ->
            require(probe.matchExpression.length in 1..MAX_PROBE_CHARS) {
                "Compiled recall probe is invalid."
            }
            require(probe.matchExpression.all { it.isLetterOrDigit() || it == '_' }) {
                "Compiled recall probe is invalid."
            }
            require(probe.matchExpression.all { it.code < 128 }) {
                "Compiled recall probe is invalid."
            }
        }

        return buildList {
            MemoryRecallProbeRouteV1.entries.forEach { route ->
                val limit = ROUTE_LIMITS.getValue(route)
                addAll(probes.filter { it.route == route }.take(limit))
            }
        }.also { selected ->
            check(selected.size <= MAX_EXECUTED_PROBES) {
                "Recall execution exceeded the probe limit."
            }
        }
    }

    private fun validateDocument(
        document: MemorySearchDocumentEntity,
        bookId: String,
        targetChapterIndex: Int,
    ) {
        check(document.rowId > 0L) { "Recall document row identity is invalid." }
        check(document.documentId.isNotBlank() && document.documentId.length <= MAX_ID_CHARS) {
            "Recall document identity is invalid."
        }
        check(document.bookId == bookId) { "Recall returned a document from another book." }
        check(document.sourceId.isNotBlank() && document.sourceId.length <= MAX_ID_CHARS) {
            "Recall source identity is invalid."
        }
        check(runCatching { MemorySearchSourceTypeV1.valueOf(document.sourceType) }.isSuccess) {
            "Recall source type is invalid."
        }
        check(document.chapterIndex == null || document.chapterIndex in 1 until targetChapterIndex) {
            "Recall returned a document outside the chapter boundary."
        }
        check(document.storyOrder == null || document.storyOrder >= 0L) {
            "Recall story order is invalid."
        }
        check(document.importance in 0..100) { "Recall importance is invalid." }
        check(document.sourceContentHash.matches(SHA256_PATTERN)) {
            "Recall source hash is invalid."
        }
        check(document.searchTerms.isNotEmpty() && document.searchTerms.all { it.code < 128 }) {
            "Recall search terms are invalid."
        }
        check(document.updatedAt >= 0L) { "Recall update time is invalid." }
    }

    private fun queryFingerprint(
        bookId: String,
        targetChapterIndex: Int,
        probes: List<MemoryRecallProbeV1>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update(0.toByte())
        }

        update(POLICY_VERSION)
        update(bookId)
        update(targetChapterIndex.toString())
        probes.forEach { probe ->
            update(probe.route.name)
            update(probe.routeOrdinal.toString())
            update(probe.matchExpression)
        }
        val hex = "0123456789abcdef"
        return buildString(64) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(hex[value ushr 4])
                append(hex[value and 0x0F])
            }
        }
    }

    private data class SourceIdentity(
        val bookId: String,
        val sourceType: String,
        val sourceId: String,
    )

    private class MutableRecallHit(
        val document: MemorySearchDocumentEntity,
    ) {
        private var targetChapterProbeHits = 0
        private var userAdditionProbeHits = 0
        private var targetArcProbeHits = 0

        fun record(route: MemoryRecallProbeRouteV1) {
            when (route) {
                MemoryRecallProbeRouteV1.TARGET_CHAPTER -> targetChapterProbeHits += 1
                MemoryRecallProbeRouteV1.USER_ADDITION -> userAdditionProbeHits += 1
                MemoryRecallProbeRouteV1.TARGET_ARC -> targetArcProbeHits += 1
            }
        }

        fun toHit(): MemorySearchRecallHitV1 = MemorySearchRecallHitV1(
            document = document,
            targetChapterProbeHits = targetChapterProbeHits,
            userAdditionProbeHits = userAdditionProbeHits,
            targetArcProbeHits = targetArcProbeHits,
        )
    }

    private companion object {
        const val POLICY_VERSION = "zhijuan.memory-search-recall.v1"
        const val MAX_ID_CHARS = 256
        const val MAX_PROBE_CHARS = 128
        const val MAX_EXECUTED_PROBES = 64
        const val MAX_DOCUMENTS_PER_PROBE = 16
        const val MAX_RETURNED_DOCUMENTS = 128

        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val ROUTE_LIMITS = mapOf(
            MemoryRecallProbeRouteV1.TARGET_CHAPTER to 32,
            MemoryRecallProbeRouteV1.USER_ADDITION to 16,
            MemoryRecallProbeRouteV1.TARGET_ARC to 16,
        )
        val HIT_ORDER = Comparator<MemorySearchRecallHitV1> { left, right ->
            compareValues(right.targetChapterProbeHits, left.targetChapterProbeHits)
                .takeIf { it != 0 }
                ?: compareValues(right.userAdditionProbeHits, left.userAdditionProbeHits)
                    .takeIf { it != 0 }
                ?: compareValues(right.targetArcProbeHits, left.targetArcProbeHits)
                    .takeIf { it != 0 }
                ?: compareValues(right.totalProbeHits, left.totalProbeHits)
                    .takeIf { it != 0 }
                ?: compareValues(right.document.importance, left.document.importance)
                    .takeIf { it != 0 }
                ?: compareNullableDescending(left.document.chapterIndex, right.document.chapterIndex)
                    .takeIf { it != 0 }
                ?: compareNullableDescending(left.document.storyOrder, right.document.storyOrder)
                    .takeIf { it != 0 }
                ?: left.document.documentId.compareTo(right.document.documentId)
        }

        fun <T : Comparable<T>> compareNullableDescending(left: T?, right: T?): Int = when {
            left == null && right == null -> 0
            left == null -> 1
            right == null -> -1
            else -> right.compareTo(left)
        }
    }
}
