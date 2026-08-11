package app.zhijuan.reader.library.di

import app.zhijuan.core.contract.LibraryRepository
import app.zhijuan.reader.library.PersistentLibraryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryContractModule {
    @Binds
    @Singleton
    abstract fun bindLibraryRepository(implementation: PersistentLibraryRepository): LibraryRepository
}
