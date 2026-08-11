package app.zhijuan.reader.connection.di

import app.zhijuan.core.contract.CurrentConnectionGateway
import app.zhijuan.reader.connection.ConnectionWizardGateway
import app.zhijuan.reader.connection.ConnectionGatewayActions
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionContractModule {
    @Binds
    @Singleton
    abstract fun bindConnectionGatewayActions(
        implementation: ConnectionWizardGateway,
    ): ConnectionGatewayActions

    @Binds
    @Singleton
    abstract fun bindCurrentConnectionGateway(
        implementation: ConnectionWizardGateway,
    ): CurrentConnectionGateway
}
