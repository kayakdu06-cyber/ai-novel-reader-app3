package app.zhijuan.feature.generation

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.ChapterCandidateArtifactSealRepositoryV1
import app.zhijuan.core.database.generation.ChapterConsistencyOutcomeRepositoryV1
import app.zhijuan.core.database.generation.ChapterContextAssemblyRepository
import app.zhijuan.core.database.generation.ChapterDraftContinuationRepository
import app.zhijuan.core.database.generation.ChapterFinalCandidateCommitRepositoryV1
import app.zhijuan.core.database.generation.ChapterFinalCandidateRecoveryRepository
import app.zhijuan.core.database.generation.ChapterPlanV2CommitRepository
import app.zhijuan.core.database.generation.ChapterPlanV2PromptSourcesRepository
import app.zhijuan.core.database.generation.ChapterPostAnalysisPromptSourcesRepository
import app.zhijuan.core.database.generation.GenerationOutputValidationRepository
import app.zhijuan.core.database.generation.GenerationRunnerExecutionLeaseRepository
import app.zhijuan.core.database.generation.GenerationRunnerQueueRepository
import app.zhijuan.core.database.generation.GenerationRunnerStageRoute
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.InitialChapterDraftPromptSourcesRepository
import app.zhijuan.core.database.generation.InitialPlanningCommitRepository
import app.zhijuan.core.database.generation.InitialPlanningPromptSourcesRepository
import app.zhijuan.core.security.AndroidProtectedArtifactStore

/** The only assembled runtime allowed to execute the finite registered routes. */
class GenerationPersistentRuntimeV1 internal constructor(
    val runner: GenerationTotalRunnerPort,
    val registeredRoutes: Set<GenerationRunnerStageRoute>,
) {
    init {
        require(registeredRoutes == GenerationRunnerExecutorRegistryPolicyV1.registeredRoutes)
    }

    override fun toString(): String =
        "GenerationPersistentRuntimeV1(routeCount=${registeredRoutes.size}, dependencies=redacted)"
}

/**
 * Constructs one queue, one exact-token registry, and one total runner over a shared database and
 * protected artifact store. Provider selection remains an injected boundary, so TASK-128 tests can
 * use Fake Provider while production Provider connection remains deferred to TASK-132.
 */
object GenerationPersistentRuntimeFactoryV1 {
    fun create(
        database: ZhijuanDatabase,
        artifactStore: AndroidProtectedArtifactStore,
        remote: GenerationBoundRemoteExecutionProvider,
        clock: GenerationExecutionClock = SystemGenerationExecutionClock,
        maximumStagesPerRun: Int = 32,
    ): GenerationPersistentRuntimeV1 {
        val states = GenerationStateRepository(database)
        val executionLeases = GenerationRunnerExecutionLeaseRepository(database)
        val queue = GenerationRunnerQueueRepository(database)
        val requests = GenerationStreamingDraftRepository(database, artifactStore)
        val outputs = GenerationOutputValidationRepository(database, artifactStore)
        val audited = AuditedStreamingProviderExecutor(requests, outputs, clock)
        val validation = StructuredOutputValidationCoordinator(outputs)
        val candidateArtifacts = ChapterCandidateArtifactSealRepositoryV1(database, artifactStore)

        val planExecutor = PersistentChapterPlanV2BoundExecutorV1(
            sources = ChapterPlanV2BoundSourceLoader.from(ChapterPlanV2PromptSourcesRepository(database)),
            remote = remote,
            requests = requests,
            coordinator = ChapterPlanV2Coordinator(
                executor = audited,
                validation = validation,
                commits = ChapterPlanV2CommitRepository(database, artifactStore),
                clock = clock,
            ),
        )
        val initialDraftExecutor = PersistentInitialChapterDraftBoundExecutorV1(
            sources = InitialChapterDraftBoundSourceLoader.from(
                InitialChapterDraftPromptSourcesRepository(database),
            ),
            remote = remote,
            requests = requests,
            coordinator = InitialChapterDraftCoordinator(
                drafts = ChapterDraftStreamingCoordinator(
                    executor = audited,
                    continuations = ChapterDraftContinuationRepository(database, artifactStore),
                    clock = clock,
                ),
                outputs = outputs,
                seals = candidateArtifacts,
                clock = clock,
            ),
        )
        val postAnalysisExecutor = PersistentChapterPostAnalysisBoundExecutorV1(
            sources = ChapterPostAnalysisBoundSourceLoader.from(
                ChapterPostAnalysisPromptSourcesRepository(database, artifactStore),
            ),
            remote = remote,
            requests = requests,
            coordinator = ChapterPostAnalysisCoordinatorV1(
                executor = audited,
                validation = validation,
                clock = clock,
            ),
            routing = ChapterPostAnalysisRoutingCoordinatorV1(
                ChapterConsistencyOutcomeRepositoryV1(database, artifactStore),
            ),
            clock = clock,
        )
        val finalCommitCoordinator = ChapterFinalCandidateCommitCoordinatorV1(
            recoveryRepository = ChapterFinalCandidateRecoveryRepository(database),
            artifactRecovery = ChapterFinalCandidateArtifactRecoveryCoordinator.forProtectedStore(artifactStore),
            generationStateRepository = states,
            finalCommitRepository = ChapterFinalCandidateCommitRepositoryV1(database, artifactStore),
        )
        val registry = GenerationRunnerExecutorRegistryV1(
            initialPlanningExecutor = PersistentInitialPlanningBoundExecutorV1(
                sources = InitialPlanningBoundSourceLoader.from(
                    InitialPlanningPromptSourcesRepository(database, artifactStore),
                ),
                remote = remote,
                requests = requests,
                executor = audited,
                validation = validation,
                commits = InitialPlanningCommitRepository(database, artifactStore),
                clock = clock,
            ),
            finalCommitExecutor = ChapterFinalCandidateCommitStageExecutorV1(
                generationStateRepository = states,
                finalCommitCoordinator = finalCommitCoordinator,
            ),
            contextAssemblyExecutor = ChapterContextAssemblyRepository(database),
            chapterPlanV2Executor = planExecutor,
            initialChapterDraftExecutor = initialDraftExecutor,
            chapterPostAnalysisExecutor = postAnalysisExecutor,
        )
        val runner = GenerationPersistentTotalRunnerV1(
            queue = queue,
            executionLeases = executionLeases,
            states = states,
            registry = registry,
            heartbeatEnvelope = GenerationRunnerHeartbeatEnvelope(executionLeases, states, clock),
            clock = clock,
            maxStagesPerRun = maximumStagesPerRun,
        )
        return GenerationPersistentRuntimeV1(runner, registry.registeredRoutes)
    }
}
