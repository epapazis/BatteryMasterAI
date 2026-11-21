package com.batterymaster.ai.di

import android.content.Context
import com.batterymaster.ai.data.prefs.SettingsRepository
import com.batterymaster.ai.data.repo.BatteryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Broadcast receivers get dependencies through an entry point rather than field injection,
 * which avoids Hilt's super.onReceive() requirement on abstract BroadcastReceiver.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiverEntryPoint {
    fun settings(): SettingsRepository
    fun battery(): BatteryRepository

    companion object {
        fun get(context: Context): ReceiverEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
    }
}
