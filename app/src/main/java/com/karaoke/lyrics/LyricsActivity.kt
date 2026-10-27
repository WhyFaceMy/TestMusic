package com.karaoke.lyrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/** Полноэкранное караоке. Работает и поверх экрана блокировки (showWhenLocked). */
class LyricsActivity : ComponentActivity() {

    companion object {
        private val _visible = MutableStateFlow(false)
        val visible: StateFlow<Boolean> = _visible.asStateFlow()

        /** Если музыка на паузе дольше этого времени — закрываемся, чтобы не тратить батарею. */
        private const val IDLE_CLOSE_MS = 60_000L
    }

    private lateinit var offsetLabel: TextView
    private lateinit var playPause: TextView

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Prefs.settings.value.closeOnUnlock) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(false)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(buildUi())

        ContextCompat.registerReceiver(
            this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Экран не гаснет, только пока музыка играет.
                    combine(Prefs.settings, MediaTracker.playback) { s, p -> s.keepScreenOn && p.playing }
                        .distinctUntilChanged()
                        .collect { keepOn ->
                            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                }
                launch {
                    MediaTracker.playback.map { it.playing }.distinctUntilChanged().collectLatest { playing ->
                        playPause.text = if (playing) "❚❚" else "▶"
                        if (!playing) {
                            delay(IDLE_CLOSE_MS)
                            finish()
                        }
                    }
                }
                launch {
                    Prefs.settings.map { it.syncOffsetMs }.distinctUntilChanged().collect { ms ->
                        offsetLabel.text = if (ms == 0) "" else
                            "Сдвиг текста: %+.1f с".format(Locale.getDefault(), ms / 1000f)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        _visible.value = true
        LockScreenLauncher.cancelNotification(this)
    }

    override fun onPause() {
        _visible.value = false
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(unlockReceiver)
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val panel = KaraokePanel(this, compact = false)
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ).apply { setMargins(dp(16), 0, dp(16), 0) },
        )

        val close = button("✕") { finish() }
        root.addView(
            close,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
            ),
        )

        offsetLabel = TextView(this).apply {
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        playPause = button("▶") { MediaTracker.playPause() }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(button("−0.5с") { shiftOffset(-500) })
            addView(button("⏮") { MediaTracker.previous() })
            addView(playPause)
            addView(button("⏭") { MediaTracker.next() })
            addView(button("+0.5с") { shiftOffset(500) })
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(offsetLabel)
            addView(controls)
        }
        root.addView(
            bottom,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
            ),
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom + dp(16))
            insets
        }
        return root
    }

    private fun shiftOffset(deltaMs: Int) {
        Prefs.update { it.copy(syncOffsetMs = (it.syncOffsetMs + deltaMs).coerceIn(-10_000, 10_000)) }
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        minWidth = dp(56)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        setBackgroundResource(ripple.resourceId)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
