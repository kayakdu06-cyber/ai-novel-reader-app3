package app.zhijuan.reader.generation.di

import app.zhijuan.core.contract.GenerationController
import app.zhijuan.core.contract.GenerationStarter
import app.zhijuan.feature.generation.GenerationBoundRemoteExecutionProvider
import app.zhijuan.feature.generation.GenerationTotalRunnerPort
import app.zhijuan.reader.generation.ForegroundGenerationGateway
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GenerationContractModule {
    @BindsOptionalOf
    internal abstract fun optionalGenerationBoundRemoteExecutionProvider():
        GenerationBoundRemoteExecutionProvider

    @Binds
    @Singleton
    internal abstract fun bindGenerationController(
        implementation: ForegroundGenerationGateway,
    ): GenerationController

    @Binds
    @Singleton
    internal abstract fun bindGenerationStarter(
        implementation: ForegroundGenerationGateway,
    ): GenerationStarter

    @Binds
    @Singleton
    internal abstract fun bindGenerationTotalRunner(
        implementation: ForegroundGenerationGateway,
    ): GenerationTotalRunnerPort
}
