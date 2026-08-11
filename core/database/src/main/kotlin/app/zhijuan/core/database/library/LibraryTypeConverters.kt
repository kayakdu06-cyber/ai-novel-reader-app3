package app.zhijuan.core.database.library

import androidx.room.TypeConverter
import app.zhijuan.core.diagnostics.GenerationTimingPhase
import app.zhijuan.core.diagnostics.GenerationTimingMilestone
import app.zhijuan.core.diagnostics.GenerationTimingOutcome
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.BudgetReservationStatus
import app.zhijuan.core.model.BudgetScope
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.ChapterStatus
import app.zhijuan.core.model.ChapterVersionSource
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.MemorySource
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.StoryEntityType
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.model.UsageSource
import app.zhijuan.core.model.RevisionSource
import app.zhijuan.core.model.TemplateOriginType
import app.zhijuan.core.model.TemplateTagDimension
import app.zhijuan.core.model.TemplateTagSource
import app.zhijuan.core.model.TemplateUseMode

class LibraryTypeConverters {
    @TypeConverter
    fun budgetScope(value: BudgetScope): String = value.name

    @TypeConverter
    fun budgetScope(value: String): BudgetScope = enumValueOf(value)

    @TypeConverter
    fun budgetReservationStatus(value: BudgetReservationStatus): String = value.name

    @TypeConverter
    fun budgetReservationStatus(value: String): BudgetReservationStatus = enumValueOf(value)

    @TypeConverter
    fun generationTimingPhase(value: GenerationTimingPhase): String = value.name

    @TypeConverter
    fun generationTimingPhase(value: String): GenerationTimingPhase = enumValueOf(value)

    @TypeConverter
    fun generationTimingMilestone(value: GenerationTimingMilestone): String = value.name

    @TypeConverter
    fun generationTimingMilestone(value: String): GenerationTimingMilestone = enumValueOf(value)

    @TypeConverter
    fun generationTimingOutcome(value: GenerationTimingOutcome?): String? = value?.name

    @TypeConverter
    fun generationTimingOutcome(value: String?): GenerationTimingOutcome? = value?.let(::enumValueOf)

    @TypeConverter
    fun bookStatus(value: BookStatus): String = value.name

    @TypeConverter
    fun bookStatus(value: String): BookStatus = enumValueOf(value)

    @TypeConverter
    fun bookLengthMode(value: BookLengthMode): String = value.name

    @TypeConverter
    fun bookLengthMode(value: String): BookLengthMode = enumValueOf(value)

    @TypeConverter
    fun titleSource(value: TitleSource): String = value.name

    @TypeConverter
    fun titleSource(value: String): TitleSource = enumValueOf(value)

    @TypeConverter
    fun chapterStatus(value: ChapterStatus): String = value.name

    @TypeConverter
    fun chapterStatus(value: String): ChapterStatus = enumValueOf(value)

    @TypeConverter
    fun consistencyStatus(value: ConsistencyStatus): String = value.name

    @TypeConverter
    fun consistencyStatus(value: String): ConsistencyStatus = enumValueOf(value)

    @TypeConverter
    fun chapterVersionSource(value: ChapterVersionSource): String = value.name

    @TypeConverter
    fun chapterVersionSource(value: String): ChapterVersionSource = enumValueOf(value)

    @TypeConverter
    fun generationJobStatus(value: GenerationJobStatus): String = value.name

    @TypeConverter
    fun generationJobStatus(value: String): GenerationJobStatus = enumValueOf(value)

    @TypeConverter
    fun generationJobType(value: GenerationJobType): String = value.name

    @TypeConverter
    fun generationJobType(value: String): GenerationJobType = enumValueOf(value)

    @TypeConverter
    fun generationPhase(value: GenerationPhase): String = value.name

    @TypeConverter
    fun generationPhase(value: String): GenerationPhase = enumValueOf(value)

    @TypeConverter
    fun generationStageStatus(value: GenerationStageStatus): String = value.name

    @TypeConverter
    fun generationStageStatus(value: String): GenerationStageStatus = enumValueOf(value)

    @TypeConverter
    fun generationTargetType(value: GenerationTargetType): String = value.name

    @TypeConverter
    fun generationTargetType(value: String): GenerationTargetType = enumValueOf(value)

    @TypeConverter
    fun requestAttemptStatus(value: RequestAttemptStatus): String = value.name

    @TypeConverter
    fun requestAttemptStatus(value: String): RequestAttemptStatus = enumValueOf(value)

    @TypeConverter
    fun standardErrorCode(value: StandardErrorCode?): String? = value?.name

    @TypeConverter
    fun standardErrorCode(value: String?): StandardErrorCode? = value?.let(::enumValueOf)

    @TypeConverter
    fun usageSource(value: UsageSource): String = value.name

    @TypeConverter
    fun usageSource(value: String): UsageSource = enumValueOf(value)

    @TypeConverter
    fun usageLedgerStatus(value: UsageLedgerStatus): String = value.name

    @TypeConverter
    fun usageLedgerStatus(value: String): UsageLedgerStatus = enumValueOf(value)

    @TypeConverter
    fun revisionSource(value: RevisionSource): String = value.name

    @TypeConverter
    fun revisionSource(value: String): RevisionSource = enumValueOf(value)

    @TypeConverter
    fun outlineNodeType(value: OutlineNodeType): String = value.name

    @TypeConverter
    fun outlineNodeType(value: String): OutlineNodeType = enumValueOf(value)

    @TypeConverter
    fun derivedDataStatus(value: DerivedDataStatus): String = value.name

    @TypeConverter
    fun derivedDataStatus(value: String): DerivedDataStatus = enumValueOf(value)

    @TypeConverter
    fun storyEntityType(value: StoryEntityType): String = value.name

    @TypeConverter
    fun storyEntityType(value: String): StoryEntityType = enumValueOf(value)

    @TypeConverter
    fun adultStatus(value: AdultStatus): String = value.name

    @TypeConverter
    fun adultStatus(value: String): AdultStatus = enumValueOf(value)

    @TypeConverter
    fun canonLevel(value: CanonLevel): String = value.name

    @TypeConverter
    fun canonLevel(value: String): CanonLevel = enumValueOf(value)

    @TypeConverter
    fun foreshadowStatus(value: ForeshadowStatus): String = value.name

    @TypeConverter
    fun foreshadowStatus(value: String): ForeshadowStatus = enumValueOf(value)

    @TypeConverter
    fun memorySource(value: MemorySource): String = value.name

    @TypeConverter
    fun memorySource(value: String): MemorySource = enumValueOf(value)

    @TypeConverter
    fun templateOriginType(value: TemplateOriginType): String = value.name

    @TypeConverter
    fun templateOriginType(value: String): TemplateOriginType = enumValueOf(value)

    @TypeConverter
    fun templateUseMode(value: TemplateUseMode): String = value.name

    @TypeConverter
    fun templateUseMode(value: String): TemplateUseMode = enumValueOf(value)

    @TypeConverter
    fun templateTagDimension(value: TemplateTagDimension): String = value.name

    @TypeConverter
    fun templateTagDimension(value: String): TemplateTagDimension = enumValueOf(value)

    @TypeConverter
    fun templateTagSource(value: TemplateTagSource): String = value.name

    @TypeConverter
    fun templateTagSource(value: String): TemplateTagSource = enumValueOf(value)

    @TypeConverter
    fun futureChapterPolicy(value: FutureChapterPolicy): String = value.name

    @TypeConverter
    fun futureChapterPolicy(value: String): FutureChapterPolicy = enumValueOf(value)

    @TypeConverter
    fun chapterEditRebuildExecutionStatus(value: ChapterEditRebuildExecutionStatus): String = value.name

    @TypeConverter
    fun chapterEditRebuildExecutionStatus(value: String): ChapterEditRebuildExecutionStatus = enumValueOf(value)

    @TypeConverter
    fun chapterEditRebuildExecutionStepType(value: ChapterEditRebuildExecutionStepType): String = value.name

    @TypeConverter
    fun chapterEditRebuildExecutionStepType(value: String): ChapterEditRebuildExecutionStepType = enumValueOf(value)

    @TypeConverter
    fun chapterEditRebuildPreparedStepState(value: ChapterEditRebuildPreparedStepState): String = value.name

    @TypeConverter
    fun chapterEditRebuildPreparedStepState(value: String): ChapterEditRebuildPreparedStepState = enumValueOf(value)
}
