package app.zhijuan.reader.generation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.LibraryDatabaseGuards
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.feature.generation.GenerationBoundRemoteExecutionProvider
import app.zhijuan.feature.generation.GenerationPersistentRuntimeFactoryV1
import app.zhijuan.provider.fake.FakeProviderAdapter
import app.zhijuan.provider.fake.fakeStreamScript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationPersistentRuntimeAndroidTest {
    private lateinit var database: ZhijuanDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ZhijuanDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun factoryExposesOneRunnerAndExactlyTheFiniteTask128Routes() {
        val fake = FakeProviderAdapter(fakeStreamScript { started(); completed() })
        val remote = GenerationBoundRemoteExecutionProvider { _, _ -> error("not opened") }

        val runtime = GenerationPersistentRuntimeFactoryV1.create(
            database = database,
            artifactStore = AndroidProtectedArtifactStore(ApplicationProvider.getApplicationContext()),
            remote = remote,
        )

        assertEquals(5, runtime.registeredRoutes.size)
        assertEquals(0L, fake.stats.snapshot().generateCalls)
    }
}
