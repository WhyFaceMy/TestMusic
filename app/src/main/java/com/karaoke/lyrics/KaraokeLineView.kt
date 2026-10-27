package com.karaoke.lyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.View

/**
 * Одна строка караоке.
 *  - Режим караоке: текст закрашивается слева направо по мере пения ([progress] 0..1).
 *    Если строка не влезает в ширину экрана, она плавно едет влево вслед за закраской.
 *  - Режим [marquee]: обычная бегущая строка (когда текст песни не найден).
 */
class KaraokeLineView(context: Context) : View(context) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = 0x80FFFFFF.toInt()
    }
    private val sungPaint = Paint(basePaint).apply {
        color = 0xFFFFD54F.toInt()
        setShadowLayer(12f, 0f, 0f, 0x99FFB300.toInt())
    }
    private val plainPaint = Paint(basePaint).apply { color = 0xFFFFFFFF.toInt() }

    private var textWidth = 0f
    private var marqueeStartedAt = SystemClock.uptimeMillis()

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            textWidth = basePaint.measureText(value)
            marqueeStartedAt = SystemClock.uptimeMillis()
            invalidate()
        }

    var progress: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var marquee: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            marqueeStartedAt = SystemClock.uptimeMillis()
            invalidate()
        }

    fun setTextSizeSp(sp: Float) {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        if (basePaint.textSize == px) return
        listOf(basePaint, sungPaint, plainPaint).forEach { it.textSize = px }
        textWidth = basePaint.measureText(text)
        requestLayout()
        invalidate()
    }

    fun setColors(sung: Int, unsung: Int, plain: Int = 0xFFFFFFFF.toInt()) {
        sungPaint.color = sung
        basePaint.color = unsung
        plainPaint.color = plain
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = basePaint.fontMetrics
        val h = (fm.descent - fm.ascent + paddingTop + paddingBottom).toInt() + 1
        setMeasuredDimension(
            getDefaultSize((textWidth + paddingLeft + paddingRight).toInt(), widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val avail = right - left
        val baseline = paddingTop + (height - paddingTop - paddingBottom) / 2f -
            (basePaint.descent() + basePaint.ascent()) / 2f

        canvas.save()
        canvas.clipRect(left, 0f, right, height.toFloat())

        if (marquee) {
            drawMarquee(canvas, left, avail, baseline)
        } else {
            val x = if (textWidth <= avail) {
                left + (avail - textWidth) / 2f
            } else {
                // Держим «точку пения» примерно на 45% ширины, пока строка не доедет до конца.
                val head = progress * textWidth
                left - (head - avail * 0.45f).coerceIn(0f, textWidth - avail)
            }
            canvas.drawText(text, x, baseline, basePaint)
            if (progress > 0f) {
                canvas.clipRect(x, 0f, x + progress * textWidth, height.toFloat())
                canvas.drawText(text, x, baseline, sungPaint)
            }
        }
        canvas.restore()
    }

    private fun drawMarquee(canvas: Canvas, left: Float, avail: Float, baseline: Float) {
        if (textWidth <= avail) {
            canvas.drawText(text, left + (avail - textWidth) / 2f, baseline, plainPaint)
            return
        }
        val pxPerMs = resources.displayMetrics.density * 0.06f // ~60 dp в секунду
        val gap = avail / 3f
        val cycle = textWidth + gap
        val elapsed = (SystemClock.uptimeMillis() - marqueeStartedAt - 1_200).coerceAtLeast(0)
        val offset = (elapsed * pxPerMs) % cycle
        canvas.drawText(text, left - offset, baseline, plainPaint)
        canvas.drawText(text, left - offset + cycle, baseline, plainPaint)
        postInvalidateOnAnimation()
    }
}
