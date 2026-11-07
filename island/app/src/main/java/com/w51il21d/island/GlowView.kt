package com.w51il21d.island

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Light around the pill, drawn behind it:
 * BREATHE = slow soft glow in the album colour while music plays,
 * SWEEP = a light running once around the edge (charging),
 * FLASH = a short halo when something arrives.
 */
class GlowView(context: Context, private val pill: View) : View(context) {
    enum class Effect { NONE, BREATHE, SWEEP, FLASH }

    private var effect = Effect.NONE
    private var color = Color.WHITE
    private var started = 0L
    private val d = context.resources.displayMetrics.density
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(9 * d, BlurMaskFilter.Blur.NORMAL) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d }
    private val edgeBlur = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f * d; maskFilter = BlurMaskFilter(5 * d, BlurMaskFilter.Blur.NORMAL) }
    private val r = RectF()
    private val m = Matrix()

    /** Radius of the pill's corners, set by the island each frame. */
    var corner = 0f


    fun play(e: Effect, c: Int) {
        if (e == Effect.BREATHE && effect == Effect.BREATHE && c == color) { invalidate(); return }
        effect = e; color = c; started = SystemClock.uptimeMillis()
        invalidate()
    }

    fun stop() { if (effect != Effect.NONE) { effect = Effect.NONE; invalidate() } }

    val playing get() = effect

    override fun onDraw(canvas: Canvas) {
        if (effect == Effect.NONE || pill.visibility != VISIBLE) return
        val t = (SystemClock.uptimeMillis() - started).toFloat()
        val l = pill.left + pill.translationX
        val tp = pill.top + pill.translationY
        r.set(l, tp, l + pill.width * pill.scaleX, tp + pill.height * pill.scaleY)
        // scale is around pivot (centre-x, top)
        val cx = pill.left + pill.translationX + pill.width / 2f
        r.left = cx - pill.width * pill.scaleX / 2f; r.right = cx + pill.width * pill.scaleX / 2f
        val rad = corner
        when (effect) {
            Effect.BREATHE -> {
                val a = 0.30f + 0.22f * (0.5f + 0.5f * sin(t / 1300f * 2 * PI).toFloat())
                halo.color = withAlpha(color, a)
                canvas.drawRoundRect(r, rad, rad, halo)
                postInvalidateDelayed(40)
            }
            Effect.SWEEP -> {
                val dur = 1100f
                if (t > dur) { effect = Effect.NONE; return }
                val fade = if (t > dur - 300) (dur - t) / 300f else 1f
                val sg = SweepGradient(r.centerX(), r.centerY(),
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(color, fade), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.55f, 0.85f, 1f))
                m.setRotate(360f * t / dur - 90f, r.centerX(), r.centerY()); sg.setLocalMatrix(m)
                edge.shader = sg; edgeBlur.shader = sg
                canvas.drawRoundRect(r, rad, rad, edgeBlur)
                canvas.drawRoundRect(r, rad, rad, edge)
                halo.color = withAlpha(color, 0.25f * fade)
                canvas.drawRoundRect(r, rad, rad, halo)
                postInvalidateOnAnimation()
            }
            Effect.FLASH -> {
                val dur = 950f
                if (t > dur) { effect = Effect.NONE; return }
                val a = if (t < 250) t / 250f else (dur - t) / (dur - 250)
                halo.color = withAlpha(color, 0.75f * a)
                canvas.drawRoundRect(r, rad, rad, halo)
                postInvalidateOnAnimation()
            }
            Effect.NONE -> {}
        }
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}
