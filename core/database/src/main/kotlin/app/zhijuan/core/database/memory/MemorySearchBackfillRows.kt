package app.zhijuan.core.database.memory

import androidx.room.ColumnInfo
import androidx.room.Embedded

internal data class EntityEventSearchBackfillRow(
    @Embedded val entityEvent: EntityEventEntity,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int?,
)

internal data class CanonFactSearchBackfillRow(
    @Embedded val canonFact: CanonFactEntity,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int?,
)

internal data class CanonFactSearchHydrationRow(
    @Embedded val canonFact: CanonFactEntity,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int?,
    @ColumnInfo(name = "bible_source_is_current") val bibleSourceIsCurrent: Boolean,
)

internal data class TimelineEventSearchBackfillRow(
    @Embedded val timelineEvent: TimelineEventEntity,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int?,
)

internal data class ForeshadowSearchBackfillRow(
    @Embedded val foreshadow: ForeshadowItemEntity,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int?,
)
