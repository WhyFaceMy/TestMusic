package com.karaoke.lyrics

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        LyricsController.start(appScope)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                LockScreenLauncher.CHANNEL_ID,
                "Караоке на экране блокировки",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Нужен, чтобы показать текст песни на заблокированном экране"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }
}
