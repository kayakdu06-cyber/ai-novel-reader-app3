package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.FinalUsageCommit
import app.zhijuan.core.database.generation.FirstChapterFastLaneCommitDraft

object FirstChapterFastLanePersistenceMapper {
    fun map(
        bootstrap: FirstChapterBootstrapV1,
        usage: FinalUsageCommit,
        committedAt: Long,
    ): FirstChapterFastLaneCommitDraft = FirstChapterFastLaneCommitDraft(
        schemaId = FirstChapterBootstrapOutputContractV1.schemaId,
        canonicalJson = bootstrap.canonicalJson,
        contentHash = bootstrap.contentHash,
        seedContentHash = bootstrap.seedContentHash,
        characterCount = bootstrap.characters.size,
        usage = usage,
        committedAt = committedAt,
    )
}
