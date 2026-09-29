package com.gmailorg.carradio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

class CarWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = resources.displayMetrics.density
        alpha = 210
    }

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#07131D"))

        val bars = 72
        val step = width.toFloat() / bars
        val center = height / 2f
        val seed = titleSeed.hashCode().toLong()

        for (i in 0 until bars) {
            val x = i * step + step * 0.5f
            val wave =
                abs(sin((i + (seed and 31).toInt()) * 0.47)) * 0.55 +
                    abs(sin((i + ((seed shr 5) and 63).toInt()) * 0.19)) * 0.35 +
                    0.10
            val half = (height * 0.43f * wave.toFloat()).coerceAtLeast(2f)
            paint.color = accentColor
            paint.alpha = if (i.toFloat() / bars <= progress) 245 else 105
            paint.strokeWidth = (step * 0.56f).coerceAtLeast(2f)
            canvas.drawLine(x, center - half, x, center + half, paint)
        }

        val px = width * progress
        canvas.drawLine(px, 0f, px, height.toFloat(), playheadPaint)
    }
}
