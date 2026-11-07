package com.w51il21d.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** Little music bars. Moves at about 20 frames a second while playing, still when paused. */
class EqView(context: Context, color: Int, private val playing: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val speed = floatArrayOf(7.1f, 9.3f, 6.2f, 8.4f)
    private val phase = floatArrayOf(0f, 1.3f, 2.1f, 0.6f)

    override fun onDraw(canvas: Canvas) {
        val n = 4
        val gap = width * 0.12f
        val bw = (width - gap * (n - 1)) / n
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until n) {
            val f = if (playing) 0.25f + 0.75f * abs(sin(t * speed[i] * 0.5f + phase[i])) else 0.22f
            val bh = height * f
            val l = i * (bw + gap)
            val top = (height - bh) / 2
            canvas.drawRoundRect(l, top, l + bw, top + bh, bw / 2, bw / 2, paint)
        }
        if (playing && isShown) postInvalidateDelayed(50)
    }
}
