package com.batterymaster.ai.di

import android.content.Context
import androidx.room.Room
import com.batterymaster.ai.data.db.AppDatabase
import com.batterymaster.ai.data.db.CapacityDao
import com.batterymaster.ai.data.db.ChargerTestDao
import com.batterymaster.ai.data.db.ProtectionDao
import com.batterymaster.ai.data.db.SampleDao
import com.batterymaster.ai.data.db.SessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Long-lived scope for work that must outlive a screen (saving calibration, exports). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

    @Provides fun sampleDao(db: AppDatabase): SampleDao = db.sampleDao()
    @Provides fun sessionDao(db: AppDatabase): SessionDao = db.sessionDao()
    @Provides fun capacityDao(db: AppDatabase): CapacityDao = db.capacityDao()
    @Provides fun protectionDao(db: AppDatabase): ProtectionDao = db.protectionDao()
    @Provides fun chargerTestDao(db: AppDatabase): ChargerTestDao = db.chargerTestDao()
}
