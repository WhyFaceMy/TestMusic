package com.karaoke.lyrics

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Служба доступа к уведомлениям. Сами уведомления не читаются — этот доступ нужен,
 * потому что только с ним Android разрешает видеть медиа-сессии чужих плееров.
 * Система держит службу запущенной постоянно, поэтому здесь же живут
 * отслеживание экрана блокировки и плавающая строка.
 */
class MediaListenerService : NotificationListenerService() {

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }

    private var scope: CoroutineScope? = null
    private var overlay: FloatingOverlay? = null
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Экран погас — заранее открываем караоке, чтобы при включении оно было на блокировке.
                Intent.ACTION_SCREEN_OFF -> LockScreenLauncher.maybeLaunch(context)
                Intent.ACTION_SCREEN_ON -> {
                    val keyguard = context.getSystemService(KeyguardManager::class.java)
                    if (keyguard.isKeyguardLocked) LockScreenLauncher.maybeLaunch(context)
                }
                Intent.ACTION_USER_PRESENT -> LockScreenLauncher.cancelNotification(context)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
        MediaTracker.start(this, ComponentName(this, MediaListenerService::class.java))

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // Системные broadcast'ы доставляются и в неэкспортированный приёмник.
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }

        if (scope == null) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = s
            overlay = FloatingOverlay(this).also { it.start(s) }
        }
    }

    override fun onListenerDisconnected() {
        cleanup()
        // Просим систему переподключить службу (бывает после обновления приложения).
        requestRebind(ComponentName(this, MediaListenerService::class.java))
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        _connected.value = false
        MediaTracker.stop()
        overlay?.hide()
        overlay = null
        scope?.cancel()
        scope = null
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
    }
}
