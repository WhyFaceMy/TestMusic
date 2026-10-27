package com.karaoke.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Плавающая бегущая строка поверх всех приложений.
 * Её можно перетаскивать по вертикали; нажатие открывает полноэкранное караоке.
 */
class FloatingOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(
                Prefs.settings,
                MediaTracker.playback,
                MediaTracker.track,
                LyricsActivity.visible,
            ) { settings, playback, track, fullScreenVisible ->
                settings.overlay && playback.playing && track != null && !fullScreenVisible
            }
                .distinctUntilChanged()
                .collectLatest { shouldShow ->
                    if (shouldShow) {
                        show()
                    } else {
                        delay(3_000) // не мигать при переключении треков и коротких паузах
                        hide()
                    }
                }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(0xD9000000.toInt())
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(KaraokePanel(context, compact = true))
        }
        val root = FrameLayout(context).apply {
            setPadding(dp(8), 0, dp(8), 0)
            addView(card)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = Prefs.settings.value.overlayY
        }

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawY = 0f
        var startY = 0
        var dragging = false
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY; startY = lp.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downRawY
                    if (abs(dy) > touchSlop) dragging = true
                    if (dragging) {
                        lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        Prefs.update { it.copy(overlayY = lp.y) }
                    } else {
                        context.startActivity(
                            Intent(context, LyricsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            true
        }

        try {
            wm.addView(root, lp)
            view = root
            params = lp
        } catch (e: Exception) {
            // Разрешение могли отозвать между проверкой и показом.
        }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }
}
