package com.karaoke.lyrics

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Открывает экран караоке поверх экрана блокировки.
 *
 * Android запрещает запускать окна из фона. Есть два легальных способа:
 *  1. У приложения есть разрешение «Поверх других приложений» — тогда запуск из фона разрешён.
 *  2. Уведомление с full-screen intent (так работают будильники и звонки).
 *     На Android 14+ для этого нужно отдельное разрешение.
 */
object LockScreenLauncher {
    const val CHANNEL_ID = "lockscreen"
    private const val NOTIFICATION_ID = 42

    fun maybeLaunch(context: Context) {
        val settings = Prefs.settings.value
        if (!settings.lockScreen || LyricsActivity.visible.value) return
        val track = MediaTracker.track.value ?: return
        if (!MediaTracker.playback.value.playing) return

        val intent = Intent(context, LyricsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                // Некоторые прошивки всё равно блокируют — пробуем через уведомление.
            }
        }
        postFullScreenNotification(context, intent, track.displayName)
    }

    fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun postFullScreenNotification(context: Context, intent: Intent, title: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val pi = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Нажмите, чтобы открыть текст песни")
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
