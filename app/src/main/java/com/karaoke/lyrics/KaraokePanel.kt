package com.karaoke.lyrics

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Блок караоке: название трека, предыдущая строка, текущая (закрашивается), следующая.
 * Каждый кадр сам берёт позицию трека из [MediaTracker] и текст из [LyricsController].
 * [compact] — урезанный вариант для плавающей строки поверх приложений.
 */
class KaraokePanel(context: Context, private val compact: Boolean) : LinearLayout(context) {

    private val header = KaraokeLineView(context).apply {
        marquee = true
        setColors(sung = 0, unsung = 0, plain = 0xB3FFFFFF.toInt())
    }
    private val status = smallText(0x99FFD54F.toInt())
    private val previous = smallText(0x66FFFFFF)
    private val current = KaraokeLineView(context)
    private val next = smallText(0x99FFFFFF.toInt())

    private var appliedTextSize = -1
    private var running = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val gap = dp(if (compact) 2 else 10)
        header.setPadding(0, 0, 0, gap)
        current.setPadding(dp(8), gap, dp(8), gap)
        val full = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        addView(header, full)
        if (!compact) {
            addView(status, full)
            addView(previous, full)
        }
        addView(current, full)
        addView(next, full)
        update()
    }

    private fun smallText(color: Int) = TextView(context).apply {
        setTextColor(color)
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            update()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    private fun applyTextSize(sp: Int) {
        if (appliedTextSize == sp) return
        appliedTextSize = sp
        val main = if (compact) sp * 0.7f else sp.toFloat()
        current.setTextSizeSp(main)
        header.setTextSizeSp(if (compact) 11f else 16f)
        next.setTextSize(TypedValue.COMPLEX_UNIT_SP, main * 0.55f)
        previous.setTextSize(TypedValue.COMPLEX_UNIT_SP, main * 0.55f)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun update() {
        val settings = Prefs.settings.value
        applyTextSize(settings.textSizeSp)

        val track = MediaTracker.track.value
        header.text = track?.displayName ?: ""
        header.visibility = if (track == null) GONE else VISIBLE

        if (track == null) {
            showMessage("Ничего не играет", "")
            return
        }

        when (val state = LyricsController.state.value) {
            is LyricsState.Ready -> {
                val pos = MediaTracker.playback.value.positionNow(track.durationMs) + settings.syncOffsetMs
                val frame = state.timeline.frameAt(pos)
                current.marquee = false
                current.text = when {
                    frame.index < 0 -> "♪"
                    frame.current.isNullOrBlank() -> "♪ ♪ ♪"
                    else -> frame.current!!
                }
                current.progress = if (frame.index < 0) 0f else frame.progress
                previous.setIfChanged(frame.previous.orEmpty())
                next.setIfChanged(frame.next.orEmpty())
                status.setIfChanged(if (state.lyrics.synced) "" else "≈ примерная синхронизация")
            }
            LyricsState.Loading, LyricsState.Idle -> showMessage("Ищу текст…", "")
            LyricsState.NotFound -> showMessage(track.displayName, "Текст не найден")
            is LyricsState.Failed -> showMessage(track.displayName, state.message)
        }
        status.visibility = if (status.text.isNullOrEmpty()) GONE else VISIBLE
    }

    private fun showMessage(main: String, statusText: String) {
        current.marquee = true
        current.text = main
        current.progress = 0f
        previous.setIfChanged("")
        next.setIfChanged(if (compact) statusText else "")
        status.setIfChanged(statusText)
        status.visibility = if (statusText.isEmpty()) GONE else VISIBLE
    }

    /** setText() всегда перестраивает разметку, а обновление идёт каждый кадр. */
    private fun TextView.setIfChanged(value: String) {
        if (text.toString() != value) text = value
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
