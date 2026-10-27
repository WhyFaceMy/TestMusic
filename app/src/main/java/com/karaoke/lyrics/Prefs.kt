package com.karaoke.lyrics

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** Показывать экран караоке на заблокированном телефоне. */
    val lockScreen: Boolean = true,
    /** Плавающая бегущая строка поверх других приложений. */
    val overlay: Boolean = false,
    /** Не гасить экран, пока играет музыка и открыт экран караоке. */
    val keepScreenOn: Boolean = true,
    /** Закрывать экран караоке после разблокировки телефона. */
    val closeOnUnlock: Boolean = true,
    val textSizeSp: Int = 30,
    val overlayY: Int = 120,
    /** Ручная подстройка синхронизации текста, мс (+ — текст раньше). */
    val syncOffsetMs: Int = 0,
)

object Prefs {
    private lateinit var sp: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val d = Settings()
        _settings.value = Settings(
            lockScreen = sp.getBoolean("lockScreen", d.lockScreen),
            overlay = sp.getBoolean("overlay", d.overlay),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            closeOnUnlock = sp.getBoolean("closeOnUnlock", d.closeOnUnlock),
            textSizeSp = sp.getInt("textSizeSp", d.textSizeSp),
            overlayY = sp.getInt("overlayY", d.overlayY),
            syncOffsetMs = sp.getInt("syncOffsetMs", d.syncOffsetMs),
        )
    }

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        _settings.value = s
        sp.edit()
            .putBoolean("lockScreen", s.lockScreen)
            .putBoolean("overlay", s.overlay)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("closeOnUnlock", s.closeOnUnlock)
            .putInt("textSizeSp", s.textSizeSp)
            .putInt("overlayY", s.overlayY)
            .putInt("syncOffsetMs", s.syncOffsetMs)
            .apply()
    }
}
