package app.zhijuan.reader.creation.di

import app.zhijuan.reader.creation.BookCreationActions
import app.zhijuan.reader.creation.BookCreationGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CreationContractModule {
    @Binds
    @Singleton
    abstract fun bindBookCreationActions(implementation: BookCreationGateway): BookCreationActions
}
