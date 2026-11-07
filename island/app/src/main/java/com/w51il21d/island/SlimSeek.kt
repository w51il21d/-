package com.w51il21d.island

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** One UI style progress bar: thin rounded track that thickens while you drag it. */
class SlimSeek(context: Context) : View(context) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onSeekStart: () -> Unit = {}
    var onSeeking: (Float) -> Unit = {}
    var onSeekEnd: (Float) -> Unit = {}
    var dragging = false
        private set
    /** Display only (downloads, deliveries): ignore touches. */
    var readOnly = false
    var fillColor: Int
        get() = fill.color
        set(v) { fill.color = v; invalidate() }

    private val d = context.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private var thick = 4f

    override fun onDraw(c: Canvas) {
        val h = thick * d
        val top = (height - h) / 2f
        c.drawRoundRect(0f, top, width.toFloat(), top + h, h / 2, h / 2, track)
        val w = maxOf(h, width * fraction)
        c.drawRoundRect(0f, top, w, top + h, h / 2, h / 2, fill)
    }

    private fun setThick(t: Float) {
        animate().cancel()
        val from = thick
        android.animation.ValueAnimator.ofFloat(from, t).apply {
            duration = 160
            addUpdateListener { thick = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (readOnly) return false
        val f = (e.x / width).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; setThick(9f); onSeekStart(); fraction = f; onSeeking(f) }
            MotionEvent.ACTION_MOVE -> { fraction = f; onSeeking(f) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; setThick(4f); fraction = f; onSeekEnd(f) }
        }
        return true
    }
}
