# TASK-060 / Phase 1C: legacy memory-index backfill audit

## Task identity

- Task: `TASK-060 / Phase 1C / Legacy production memory-index backfill`
- Repository: `D:\gptuser\projects\ai-novel-reader-app2`
- Baseline: current dirty WIP after reports 80 and 81; preserve all existing changes.
- Model: DeepSeek V4 Flash, text-only, read-only patch-proposal mode.

## Runtime budget

- Reasoning: `max`
- Maximum runtime: 20 minutes
- Total token cap: none, per user authorization
- Do not run Gradle, Android tools, network calls, or real Zhijuan provider APIs.

## Goal

Audit and propose a minimal production implementation that upgrades the database from v9 to v10 with an encrypted per-book memory-search backfill completion marker, then rebuilds all six searchable authoritative memory source types for one book in a single resumable Room transaction. The first context assembly for an unmarked book will invoke it; completed books skip, and all later production source writes remain covered by the Phase 1B atomic incremental writers.

## Verified current state

- v9 owns `memory_search_document`, its FTS4 external-content table, four sync triggers, and stable per-source replacement/deletion DAO methods.
- `MemorySearchDocumentFactoryV1` maps STORY_ENTITY, CHAPTER_SUMMARY, ENTITY_EVENT, CANON_FACT, TIMELINE_EVENT and active FORESHADOW rows.
- Phase 1B atomically wires all current production inserts/updates/stale deletes; core/database API 30 is 117/117.
- Existing v1→v9 databases migrate successfully but receive no derived search documents, so old books need one controlled app-level rebuild.

## Required reading

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `reports/2026-08-04-80-task-060-production-fts-schema.md`
5. `reports/2026-08-04-81-task-060-atomic-production-indexing.md`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDao.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentFactory.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchIndexWriter.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
13. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ZhijuanMigrationTest.kt`
14. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemoryDatabaseTest.kt`

## Proposed design constraints

- Add v10 table `memory_search_backfill_state` with `book_id` primary key/foreign key, fixed `index_schema_version`, and `completed_at`. It is an encrypted completion marker, not a source-content copy.
- `MIGRATION_9_10` creates only this marker table and necessary index; it must not attempt Chinese tokenization in SQL.
- Backfill is per book and runs in one outer `database.withTransaction`: verify book, return replay if marker version is current, delete only that book's search rows, page through all six authoritative source types in stable primary-key order, transform with the existing factory, replace documents in bounded batches, then insert the completion marker last.
- Any factory error, SQL error, cancellation, process death, or marker failure must leave both the prior book index and marker unchanged through transaction rollback. No partial index may be declared ready.
- Use keyset pagination (`source_id > afterId`), not unbounded lists or OFFSET. Page size is fixed and bounded.
- Chapter-derived rows must obtain actual chapter indexes with a join to `chapter_version` and `chapter`; do not perform one DAO lookup per row and do not guess from story order.
- Include only currently authoritative searchable rows: unarchived story entities; VALID summaries/events/facts/timeline; VALID non-resolved/non-abandoned foreshadows. Factory remains the final fail-closed gate.
- A completion marker means the legacy baseline was built. Later Phase 1B incremental writers keep it current; do not invalidate and rebuild the entire book after every generated chapter.
- Context assembly invokes the ensure-ready operation inside its existing transaction after book/job validation and before search recall is used. Phase 1C may wire the call now even though multi-route recall follows in Phase 2.
- Do not store source Chinese, JSON, prompt text, or model output in the marker.
- Keep API 30 implicit-AND compatibility and v1→v9 data intact.

## Allowed proposal scope

- One new backfill state entity/DAO registration and one backfill repository.
- Narrow page-row projection data classes and MemoryDao keyset queries.
- ZhijuanDatabase v10 registration and 9→10 migration/registry.
- One ensure-ready call in ChapterContextAssemblyRepository.
- Minimal migration and Android database tests.
- No UI, provider, generation prompt, budget policy, multi-route ranking, vector search, remote, or physical-device work.

## Required review questions

1. Does a nested DAO `@Transaction` participate safely in the one outer Room transaction?
2. What exact Room projection shape avoids duplicate columns when joining source rows to chapter index?
3. Can a trigger-induced failure after search rows are rebuilt prove old index + absent marker rollback?
4. Does the marker FK action match current book/search lifecycle without silently changing delete behavior?
5. Are there any Phase 1B writers that would make the marker stale, or do they keep the marked baseline incrementally correct?

## Acceptance criteria

- Return a minimal apply_patch-compatible proposal; actual file writes must remain 0.
- Cover schema/migration/Room mapping, keyset pagination, atomic failure, exact replay and book isolation.
- Do not claim TASK-060 complete.

## Required handoff headings

1. `完成内容`
2. `修改文件（提案，实际 0 文件）`
3. `补丁提案`
4. `事务与迁移审计`
5. `建议测试`
6. `未完成 / 风险`
7. `需要 Sol 处理`
8. `假设`
