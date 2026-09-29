package com.gmailorg.carradio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class CarWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.2f
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = resources.displayMetrics.density
        alpha = 220
    }

    private var phase = 0f
    private var lastFrameNs = 0L

    var accentColor: Int = Color.parseColor("#20B8FF")
        set(value) {
            field = value
            invalidate()
        }

    var titleSeed: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var isPlaying: Boolean = false
        set(value) {
            field = value
            lastFrameNs = 0L
            invalidate()
        }

    var energy: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#07131D"))

        val now = System.nanoTime()
        if (isPlaying) {
            if (lastFrameNs != 0L) {
                val dt = ((now - lastFrameNs) / 1_000_000_000f).coerceAtMost(0.05f)
                phase = (phase + dt * (2.2f + energy * 3.8f)) % 1000f
            }
            lastFrameNs = now
        } else {
            lastFrameNs = 0L
        }

        val bars = 72
        val step = width.toFloat() / bars
        val center = height / 2f
        val seed = titleSeed.hashCode().toLong()
        val pulse = if (isPlaying) 0.88f + 0.12f * sin(phase * 4.6f) else 0.92f
        val motion = if (isPlaying) phase else 0f

        glowPaint.color = accentColor
        glowPaint.alpha = (35 + (energy * 55f).toInt()).coerceIn(0, 120)

        for (i in 0 until bars) {
            val x = i * step + step * 0.5f
            val wave =
                abs(sin((i + (seed and 31).toInt()) * 0.47 + motion * 1.9f)) * 0.50 +
                    abs(sin((i + ((seed shr 5) and 63).toInt()) * 0.19 - motion * 1.1f)) * 0.32 +
                    abs(cos(i * 0.11 + motion * 2.6f)) * 0.08 +
                    0.10

            val boost = 1f + energy * 0.28f
            val half = (height * 0.43f * wave.toFloat() * pulse * boost).coerceAtLeast(2f)

            paint.color = accentColor
            paint.alpha = if (i.toFloat() / bars <= progress) 250 else 100
            paint.strokeWidth = (step * (0.56f + energy * 0.08f)).coerceAtLeast(2f)

            if (isPlaying || energy > 0f) {
                glowPaint.strokeWidth = paint.strokeWidth * 2.4f
                canvas.drawLine(x, center - half, x, center + half, glowPaint)
            }
            canvas.drawLine(x, center - half, x, center + half, paint)
        }

        // Neon "spin" ring: rotates while the deck is playing and gets more
        // intense during a crossfade.
        val radius = (height * 0.19f).coerceAtMost(width * 0.12f)
        val cx = width - radius - 8f * resources.displayMetrics.density
        val cy = center
        ringPaint.color = accentColor
        ringPaint.alpha = if (isPlaying) 210 else 70
        ringPaint.strokeWidth = resources.displayMetrics.density * (2.0f + energy * 2.5f)
        canvas.drawCircle(cx, cy, radius, ringPaint)

        if (isPlaying) {
            val angle = phase * (220f + energy * 300f)
            val rad = Math.toRadians(angle.toDouble())
            val dotX = cx + cos(rad).toFloat() * radius
            val dotY = cy + sin(rad).toFloat() * radius
            paint.color = Color.WHITE
            paint.alpha = 245
            canvas.drawCircle(
                dotX,
                dotY,
                resources.displayMetrics.density * (3.2f + energy * 2f),
                paint
            )
        }

        val px = width * progress
        canvas.drawLine(px, 0f, px, height.toFloat(), playheadPaint)

        if (isPlaying) {
            postInvalidateOnAnimation()
        }
    }
}
