package app.zhijuan.reader.generation.di

import app.zhijuan.core.contract.GenerationController
import app.zhijuan.reader.generation.ForegroundGenerationGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GenerationContractModule {
    @Binds
    @Singleton
    internal abstract fun bindGenerationController(
        implementation: ForegroundGenerationGateway,
    ): GenerationController
}
