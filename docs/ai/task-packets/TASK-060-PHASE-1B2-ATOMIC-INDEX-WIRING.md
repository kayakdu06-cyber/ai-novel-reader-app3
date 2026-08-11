# TASK-060 / Phase 1B2: production memory index atomic wiring audit

## Task identity

- Task: `TASK-060 / Phase 1B2 / Atomic memory-search index wiring`
- Repository: `D:\gptuser\projects\ai-novel-reader-app2`
- Baseline: current dirty WIP; preserve every existing change and do not restart the task.
- Model: DeepSeek V4 Flash, text-only, read-only patch-proposal mode.

## Runtime budget

- Reasoning: `max`
- Maximum runtime: 20 minutes
- Total token cap: none, per continuing user authorization
- Do not run Gradle, Android tools, network calls, or real Zhijuan provider APIs.
- Stop if the required change needs schema changes, new source models, or files outside the explicit scope.

## Goal

Audit the existing TASK-060 WIP and propose the smallest correct Kotlin patch that writes or removes production memory-search documents inside the same Room transactions as their authoritative memory rows. Cover fresh commits, safe idempotent replay repair, chapter-version replacement stale deletion, and mutable foreshadow updates without regressing later state.

## Current verified WIP

- Database v9 already owns `memory_search_document` plus FTS4 external-content triggers and DAO.
- `MemorySearchDocumentFactoryV1` maps six authoritative source types without storing source prose.
- `MemorySearchIndexWriterV1` provides story-bible, chapter-memory, story-tracking replacement and pre-stale identity capture.
- JVM factory/token tests pass 18/18; API 30 writer/FTS replacement test passes 6/6.
- API 30 requires portable implicit-AND FTS expressions; do not revert to explicit `AND`.

## Required reading

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/ai/task-packets/TASK-060-PHASE-1A-PRODUCTION-FTS-SCHEMA.md`
5. `docs/ai/task-packets/TASK-060-PHASE-1B1-SEARCH-DOCUMENT-FACTORY.md`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchIndexWriter.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDao.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/InitialPlanningCommitRepository.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionCommitRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionCommitRepository.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterGenerationCommitRepository.kt`
13. Directly referenced DAO methods/entities only when needed to confirm exact signatures.

## Allowed proposal scope

- The five generation commit repositories listed above.
- `MemorySearchIndexWriter.kt` only if a narrowly required helper/signature correction is proven.
- Existing directly corresponding database tests only; do not propose a broad test rewrite.

## Non-negotiable invariants

- Source rows, search rows, FTS triggers, stage/job state, and stale deletion share one outer Room transaction.
- Capture all old source identities before `markDerivedDataStaleForReplacedChapter`; delete those search rows after staling within the same transaction.
- Fresh story-bible and chapter-memory commits must index their newly inserted immutable rows.
- Replaying an immutable story-bible or chapter-memory commit may repair missing indexes only after verifying persisted rows exactly match the frozen payload.
- Fresh story-tracking commits must reload every updated foreshadow from the database after compare-and-transition, then index its actual persisted state.
- Resolved/abandoned/stale foreshadows must have their prior document deleted.
- Do not replay a historical mutable foreshadow snapshot in a way that can regress later chapter metadata or resurrect inactive search rows. If safe replay repair cannot be reconstructed from current authoritative rows, leave it unchanged and state why.
- Final-candidate and legacy monolithic chapter replacement paths must remove replaced-version search rows and index the new summary/events/facts/timeline/foreshadows atomically.
- Existing document/source collision checks, content privacy, stable row replacement, lease/state validation, and dirty WIP must remain intact.
- No real provider API, remote, commit, reset, clean, physical-device command, or file outside `D:\gptuser`.

## Review questions

1. At each insertion point, which exact persisted rows should be sent to the writer?
2. Which replay paths are safe to repair, and which must not mutate a mutable foreshadow index?
3. Does the current writer correctly delete inactive updated foreshadows and avoid assigning the wrong source chapter index?
4. What minimum tests prove commit/index atomic rollback, idempotent replay, and replacement stale cleanup?
5. Are there other production call sites among these five paths that can create authoritative indexed rows without the index?

## Acceptance criteria

- Return one minimal apply_patch-compatible proposal or exact per-file edit blocks.
- Explain fresh/replay/replacement behavior for every proposed repository change.
- Do not edit files or claim TASK-060 complete.
- Identify any unsafe ambiguity for Sol instead of guessing.

## Required handoff headings

1. `完成内容`
2. `修改文件（提案，实际 0 文件）`
3. `补丁提案`
4. `事务与 replay 审计`
5. `建议测试`
6. `未完成 / 风险`
7. `需要 Sol 处理`
8. `假设`
