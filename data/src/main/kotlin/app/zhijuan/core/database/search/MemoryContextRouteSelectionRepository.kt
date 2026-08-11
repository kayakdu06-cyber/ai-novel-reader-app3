package app.zhijuan.core.database.search

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.memory.CanonFactSearchHydrationRow
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ForeshadowSearchBackfillRow
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import java.security.MessageDigest

internal enum class MemoryContextRouteV1 {
    MANDATORY_HARD_FACT,
    MANDATORY_DUE_FORESHADOW,
    RECENT_SUMMARY,
    FTS,
}

internal enum class MemoryContextSelectionStatusV1 {
    OK,
    MANDATORY_OVERFLOW,
}

internal data class MemoryContextFtsHitEvidenceV1(
    val targetChapterProbeHits: Int,
    val userAdditionProbeHits: Int,
    val targetArcProbeHits: Int,
) {
    val totalProbeHits: Int =
        targetChapterProbeHits + userAdditionProbeHits + targetArcProbeHits

    init {
        require(targetChapterProbeHits >= 0) { "Target-chapter recall evidence is invalid." }
        require(userAdditionProbeHits >= 0) { "User-addition recall evidence is invalid." }
        require(targetArcProbeHits >= 0) { "Target-arc recall evidence is invalid." }
        require(totalProbeHits > 0) { "FTS evidence must contain a probe hit." }
    }

    override fun toString(): String =
        "MemoryContextFtsHitEvidenceV1(target=$targetChapterProbeHits, " +
            "user=$userAdditionProbeHits, arc=$targetArcProbeHits, total=$totalProbeHits)"
}

internal class MemoryContextRouteSelectionItemV1(
    val source: MemorySearchAuthoritativeSourceV1,
    val routes: Set<MemoryContextRouteV1>,
    val ftsEvidence: MemoryContextFtsHitEvidenceV1?,
    val rank: Int,
) {
    init {
        require(routes.isNotEmpty()) { "A selected memory must carry a route." }
        require((MemoryContextRouteV1.FTS in routes) == (ftsEvidence != null)) {
            "Selected-memory FTS evidence is inconsistent."
        }
        require(rank >= 1) { "Selected-memory rank is invalid." }
    }

    override fun toString(): String =
        "MemoryContextRouteSelectionItemV1(source=$source, routes=$routes, " +
            "ftsEvidence=$ftsEvidence, rank=$rank)"
}

internal data class MemoryContextRouteEvidenceV1(
    val mandatoryHardFactCount: Int,
    val mandatoryDueForeshadowCount: Int,
    val recentSummaryCount: Int,
    val hydratedFtsHitCount: Int,
    val mergedFtsHitCount: Int,
    val retainedNewFtsHitCount: Int,
    val boundedOmittedFtsHitCount: Int,
    val overflowCoreCount: Int,
    val compiledProbeCount: Int,
    val omittedCompiledProbeCount: Int,
    val executedProbeCount: Int,
    val executedTargetChapterProbeCount: Int,
    val executedUserAdditionProbeCount: Int,
    val executedTargetArcProbeCount: Int,
    val omittedExecutionProbeCount: Int,
    val omittedRankedDocumentCount: Int,
    val rejectedPointerCount: Int,
    val hardLimit: Int,
    val routeCounts: Map<MemoryContextRouteV1, Int>,
) {
    init {
        require(hardLimit in 1..MAX_SELECTION_ITEMS) { "Memory-selection hard limit is invalid." }
        require(mandatoryHardFactCount in 0..hardLimit + 1) {
            "Mandatory hard-fact count is invalid."
        }
        require(mandatoryDueForeshadowCount in 0..hardLimit + 1) {
            "Mandatory due-foreshadow count is invalid."
        }
        require(recentSummaryCount in 0..MAX_RECENT_SUMMARIES) {
            "Recent-summary count is invalid."
        }
        require(hydratedFtsHitCount in 0..MAX_FTS_HITS) { "Hydrated FTS count is invalid." }
        require(mergedFtsHitCount in 0..hydratedFtsHitCount) { "Merged FTS count is invalid." }
        require(retainedNewFtsHitCount in 0..hydratedFtsHitCount) {
            "Retained new FTS count is invalid."
        }
        require(boundedOmittedFtsHitCount in 0..hydratedFtsHitCount) {
            "Omitted FTS count is invalid."
        }
        require(
            mergedFtsHitCount + retainedNewFtsHitCount + boundedOmittedFtsHitCount ==
                hydratedFtsHitCount,
        ) { "FTS selection accounting is inconsistent." }
        require(overflowCoreCount >= 0) { "Mandatory memory overflow count is invalid." }
        require(compiledProbeCount in 0..128) { "Compiled probe count is invalid." }
        require(omittedCompiledProbeCount >= 0) { "Compiled omission count is invalid." }
        require(executedProbeCount in 0..64) { "Executed probe count is invalid." }
        require(executedTargetChapterProbeCount in 0..32) {
            "Executed target-chapter probe count is invalid."
        }
        require(executedUserAdditionProbeCount in 0..16) {
            "Executed user-addition probe count is invalid."
        }
        require(executedTargetArcProbeCount in 0..16) {
            "Executed target-arc probe count is invalid."
        }
        require(
            executedTargetChapterProbeCount + executedUserAdditionProbeCount +
                executedTargetArcProbeCount == executedProbeCount,
        ) { "Executed route accounting is inconsistent." }
        require(omittedExecutionProbeCount >= 0) { "Execution omission count is invalid." }
        require(executedProbeCount + omittedExecutionProbeCount == compiledProbeCount) {
            "Probe execution accounting is inconsistent."
        }
        require(omittedRankedDocumentCount >= 0) { "Ranked omission count is invalid." }
        require(rejectedPointerCount in 0..MAX_FTS_HITS) { "Rejected pointer count is invalid." }
        require(routeCounts.keys == MemoryContextRouteV1.entries.toSet()) {
            "Memory route evidence is incomplete."
        }
        require(routeCounts.values.all { it >= 0 }) { "Memory route count is invalid." }
    }

    override fun toString(): String =
        "MemoryContextRouteEvidenceV1(hard=$mandatoryHardFactCount, " +
            "due=$mandatoryDueForeshadowCount, recent=$recentSummaryCount, " +
            "hydratedFts=$hydratedFtsHitCount, mergedFts=$mergedFtsHitCount, " +
            "retainedNewFts=$retainedNewFtsHitCount, omittedFts=$boundedOmittedFtsHitCount, " +
            "overflowCore=$overflowCoreCount, compiled=$compiledProbeCount, " +
            "omittedCompiled=$omittedCompiledProbeCount, executed=$executedProbeCount, " +
            "omittedExecution=$omittedExecutionProbeCount, omittedRanked=$omittedRankedDocumentCount, " +
            "rejectedPointers=$rejectedPointerCount, hardLimit=$hardLimit, routeCounts=$routeCounts)"
}

internal data class MemoryContextRouteSelectionResultV1(
    val status: MemoryContextSelectionStatusV1,
    val items: List<MemoryContextRouteSelectionItemV1>,
    val evidence: MemoryContextRouteEvidenceV1,
    val queryFingerprint: String?,
    val indexRebuildRequired: Boolean,
) {
    init {
        require(items.size <= evidence.hardLimit) { "Memory selection exceeds its hard limit." }
        require(items.map { it.rank } == (1..items.size).toList()) {
            "Memory-selection ranks are not contiguous."
        }
        require(
            items.map { it.source.sourceType to it.source.sourceId }.distinct().size == items.size,
        ) { "Memory selection contains duplicate source identities." }
        require(queryFingerprint == null || queryFingerprint.matches(SHA256_PATTERN)) {
            "Memory-selection query fingerprint is invalid."
        }
        when (status) {
            MemoryContextSelectionStatusV1.OK -> {
                require(queryFingerprint != null) { "Completed memory selection lacks a fingerprint." }
                require(evidence.overflowCoreCount == 0) {
                    "Completed memory selection contains overflow evidence."
                }
            }

            MemoryContextSelectionStatusV1.MANDATORY_OVERFLOW -> {
                require(items.isEmpty()) { "Overflow memory selection must not return partial items." }
                require(queryFingerprint == null) { "Overflow memory selection cannot have a query fingerprint." }
                require(!indexRebuildRequired) { "Overflow memory selection cannot request an index rebuild." }
                require(evidence.overflowCoreCount >= 1) {
                    "Overflow memory selection lacks overflow evidence."
                }
                require(
                    evidence.hydratedFtsHitCount == 0 && evidence.compiledProbeCount == 0 &&
                        evidence.executedProbeCount == 0 && evidence.rejectedPointerCount == 0,
                ) { "Overflow memory selection must not run FTS."
                }
            }
        }
    }

    override fun toString(): String =
        "MemoryContextRouteSelectionResultV1(status=$status, itemCount=${items.size}, " +
            "evidence=$evidence, queryFingerprint=$queryFingerprint, " +
            "indexRebuildRequired=$indexRebuildRequired, items=redacted)"

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/**
 * Selects a bounded, authoritative pre-chapter memory set in one Room read transaction.
 * The caller must finish search backfill before entering this read-only operation.
 */
internal class MemoryContextRouteSelectionRepositoryV1(
    private val database: ZhijuanDatabase,
) {
    private val recall = MemorySearchRecallRepositoryV1(database)
    private val hydration = MemorySearchHydrationRepositoryV1(database)

    suspend fun select(
        bookId: String,
        targetChapterIndex: Int,
        targetChapterTitle: String,
        targetChapterPlanJson: String,
        targetArcTitle: String,
        targetArcPlanJson: String,
        userAddition: String?,
        hardLimit: Int = MAX_SELECTION_ITEMS,
    ): MemoryContextRouteSelectionResultV1 {
        require(bookId.isNotBlank() && bookId.length <= MAX_ID_CHARS) {
            "Memory-selection book identity is invalid."
        }
        require(targetChapterIndex >= 1) { "Memory-selection target chapter is invalid." }
        require(hardLimit in 1..MAX_SELECTION_ITEMS) { "Memory-selection hard limit is invalid." }

        return database.withTransaction {
            val memory = database.memoryDao()
            val head = requireNotNull(memory.findMemoryHead(bookId)) {
                "Memory-selection book head is missing."
            }
            val bible = requireNotNull(
                head.currentBibleRevisionId?.let { revisionId ->
                    memory.findBibleRevision(revisionId)
                },
            ) {
                "Memory-selection current Bible is missing."
            }
            require(bible.bookId == bookId && sha256(bible.payloadJson) == bible.contentHash) {
                "Memory-selection current Bible is invalid."
            }

            val hardFacts = memory.contextHardCanonFacts(bookId, targetChapterIndex, hardLimit + 1)
                .onEach { requireValidHardFact(it, bookId, targetChapterIndex) }
            val dueForeshadows = memory.dueForeshadowsForContext(
                bookId,
                targetChapterIndex,
                hardLimit + 1,
            ).onEach { requireValidDueForeshadow(it, bookId, targetChapterIndex) }
            val recentSummaries = memory.recentValidSummaries(
                bookId,
                targetChapterIndex,
                MAX_RECENT_SUMMARIES,
            ).onEach { requireValidRecentSummary(it, bookId, targetChapterIndex) }

            val selections = linkedMapOf<SourceIdentity, MutableSelection>()
            hardFacts.forEach { row ->
                put(
                    selections,
                    CanonFactSourceV1(
                        fact = row.canonFact,
                        chapterIndex = row.chapterIndex,
                        bibleSourceIsCurrent = row.bibleSourceIsCurrent,
                    ),
                    MemoryContextRouteV1.MANDATORY_HARD_FACT,
                )
            }
            dueForeshadows.forEach { row ->
                put(
                    selections,
                    ForeshadowSourceV1(row.foreshadow, row.chapterIndex),
                    MemoryContextRouteV1.MANDATORY_DUE_FORESHADOW,
                )
            }
            recentSummaries.forEach { summary ->
                put(selections, ChapterSummarySourceV1(summary), MemoryContextRouteV1.RECENT_SUMMARY)
            }

            if (selections.size > hardLimit) {
                return@withTransaction overflowResult(
                    hardLimit = hardLimit,
                    hardFactCount = hardFacts.size,
                    dueForeshadowCount = dueForeshadows.size,
                    recentSummaryCount = recentSummaries.size,
                    overflowCoreCount = selections.size - hardLimit,
                )
            }

            val recallResult = recall.recall(
                bookId = bookId,
                targetChapterIndex = targetChapterIndex,
                targetChapterTitle = targetChapterTitle,
                targetChapterPlanJson = targetChapterPlanJson,
                targetArcTitle = targetArcTitle,
                targetArcPlanJson = targetArcPlanJson,
                userAddition = userAddition,
            )
            val hydrationResult = hydration.hydrate(bookId, targetChapterIndex, recallResult)
            var mergedFts = 0
            var retainedNewFts = 0
            var omittedFts = 0

            hydrationResult.hits.forEach { hit ->
                val source = hit.authoritativeSource
                val identity = SourceIdentity(source.sourceType, source.sourceId)
                val ftsEvidence = MemoryContextFtsHitEvidenceV1(
                    targetChapterProbeHits = hit.recallHit.targetChapterProbeHits,
                    userAdditionProbeHits = hit.recallHit.userAdditionProbeHits,
                    targetArcProbeHits = hit.recallHit.targetArcProbeHits,
                )
                val existing = selections[identity]
                if (existing != null) {
                    check(existing.ftsEvidence == null) { "Memory selection contains duplicate FTS evidence." }
                    existing.routes += MemoryContextRouteV1.FTS
                    existing.ftsEvidence = ftsEvidence
                    mergedFts += 1
                } else if (selections.size < hardLimit) {
                    selections[identity] = MutableSelection(
                        source = source,
                        routes = linkedSetOf(MemoryContextRouteV1.FTS),
                        ftsEvidence = ftsEvidence,
                    )
                    retainedNewFts += 1
                } else {
                    omittedFts += 1
                }
            }

            val items = selections.values.mapIndexed { index, selection ->
                MemoryContextRouteSelectionItemV1(
                    source = selection.source,
                    routes = selection.routes.toSet(),
                    ftsEvidence = selection.ftsEvidence,
                    rank = index + 1,
                )
            }
            val routeCounts = MemoryContextRouteV1.entries.associateWith { route ->
                items.count { route in it.routes }
            }
            MemoryContextRouteSelectionResultV1(
                status = MemoryContextSelectionStatusV1.OK,
                items = items,
                evidence = MemoryContextRouteEvidenceV1(
                    mandatoryHardFactCount = hardFacts.size,
                    mandatoryDueForeshadowCount = dueForeshadows.size,
                    recentSummaryCount = recentSummaries.size,
                    hydratedFtsHitCount = hydrationResult.hits.size,
                    mergedFtsHitCount = mergedFts,
                    retainedNewFtsHitCount = retainedNewFts,
                    boundedOmittedFtsHitCount = omittedFts,
                    overflowCoreCount = 0,
                    compiledProbeCount = recallResult.compiledProbeCount,
                    omittedCompiledProbeCount = recallResult.omittedCompiledProbeCount,
                    executedProbeCount = recallResult.executedProbeCount,
                    executedTargetChapterProbeCount = recallResult.executedTargetChapterProbeCount,
                    executedUserAdditionProbeCount = recallResult.executedUserAdditionProbeCount,
                    executedTargetArcProbeCount = recallResult.executedTargetArcProbeCount,
                    omittedExecutionProbeCount = recallResult.omittedExecutionProbeCount,
                    omittedRankedDocumentCount = recallResult.omittedRankedDocumentCount,
                    rejectedPointerCount = hydrationResult.rejectedPointerCount,
                    hardLimit = hardLimit,
                    routeCounts = routeCounts,
                ),
                queryFingerprint = hydrationResult.queryFingerprint,
                indexRebuildRequired = hydrationResult.indexRebuildRequired,
            )
        }
    }

    private fun requireValidHardFact(
        row: CanonFactSearchHydrationRow,
        bookId: String,
        targetChapterIndex: Int,
    ) {
        val fact = row.canonFact
        val chapterSourceIsCurrent = fact.sourceChapterVersionId != null &&
            row.chapterIndex != null && row.chapterIndex in 1 until targetChapterIndex
        check(
            fact.bookId == bookId && fact.status == DerivedDataStatus.VALID &&
                fact.canonLevel == CanonLevel.HARD_CANON &&
                (row.bibleSourceIsCurrent || chapterSourceIsCurrent),
        ) { "Mandatory hard-fact query returned an invalid row." }
    }

    private fun requireValidDueForeshadow(
        row: ForeshadowSearchBackfillRow,
        bookId: String,
        targetChapterIndex: Int,
    ) {
        val item = row.foreshadow
        val sourceIsCurrent = if (item.sourceChapterVersionId == null) {
            row.chapterIndex == null
        } else {
            row.chapterIndex != null && row.chapterIndex in 1 until targetChapterIndex
        }
        val isDue = item.targetStartChapterIndex?.let { it <= targetChapterIndex } == true ||
            item.targetEndChapterIndex?.let { it <= targetChapterIndex } == true
        check(
            item.bookId == bookId && item.memoryStatus == DerivedDataStatus.VALID &&
                item.foreshadowStatus != ForeshadowStatus.RESOLVED &&
                item.foreshadowStatus != ForeshadowStatus.ABANDONED && sourceIsCurrent && isDue,
        ) { "Mandatory foreshadow query returned an invalid row." }
    }

    private fun requireValidRecentSummary(
        summary: ChapterSummaryEntity,
        bookId: String,
        targetChapterIndex: Int,
    ) {
        check(
            summary.bookId == bookId && summary.status == DerivedDataStatus.VALID &&
                summary.chapterIndex in 1 until targetChapterIndex,
        ) { "Recent-summary query returned an invalid row." }
    }

    private fun put(
        selections: LinkedHashMap<SourceIdentity, MutableSelection>,
        source: MemorySearchAuthoritativeSourceV1,
        route: MemoryContextRouteV1,
    ) {
        val identity = SourceIdentity(source.sourceType, source.sourceId)
        val existing = selections[identity]
        if (existing == null) {
            selections[identity] = MutableSelection(source, linkedSetOf(route), null)
        } else {
            existing.routes += route
        }
    }

    private fun overflowResult(
        hardLimit: Int,
        hardFactCount: Int,
        dueForeshadowCount: Int,
        recentSummaryCount: Int,
        overflowCoreCount: Int,
    ): MemoryContextRouteSelectionResultV1 {
        val routeCounts = MemoryContextRouteV1.entries.associateWith { route ->
            when (route) {
                MemoryContextRouteV1.MANDATORY_HARD_FACT -> hardFactCount
                MemoryContextRouteV1.MANDATORY_DUE_FORESHADOW -> dueForeshadowCount
                MemoryContextRouteV1.RECENT_SUMMARY -> recentSummaryCount
                MemoryContextRouteV1.FTS -> 0
            }
        }
        return MemoryContextRouteSelectionResultV1(
            status = MemoryContextSelectionStatusV1.MANDATORY_OVERFLOW,
            items = emptyList(),
            evidence = MemoryContextRouteEvidenceV1(
                mandatoryHardFactCount = hardFactCount,
                mandatoryDueForeshadowCount = dueForeshadowCount,
                recentSummaryCount = recentSummaryCount,
                hydratedFtsHitCount = 0,
                mergedFtsHitCount = 0,
                retainedNewFtsHitCount = 0,
                boundedOmittedFtsHitCount = 0,
                overflowCoreCount = overflowCoreCount,
                compiledProbeCount = 0,
                omittedCompiledProbeCount = 0,
                executedProbeCount = 0,
                executedTargetChapterProbeCount = 0,
                executedUserAdditionProbeCount = 0,
                executedTargetArcProbeCount = 0,
                omittedExecutionProbeCount = 0,
                omittedRankedDocumentCount = 0,
                rejectedPointerCount = 0,
                hardLimit = hardLimit,
                routeCounts = routeCounts,
            ),
            queryFingerprint = null,
            indexRebuildRequired = false,
        )
    }

    private data class SourceIdentity(
        val sourceType: MemorySearchSourceTypeV1,
        val sourceId: String,
    )

    private class MutableSelection(
        val source: MemorySearchAuthoritativeSourceV1,
        val routes: LinkedHashSet<MemoryContextRouteV1>,
        var ftsEvidence: MemoryContextFtsHitEvidenceV1?,
    )

    private fun sha256(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

}

private const val MAX_SELECTION_ITEMS = 512
private const val MAX_RECENT_SUMMARIES = 8
private const val MAX_FTS_HITS = 128
private const val MAX_ID_CHARS = 256
