package com.w51il21d.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Idle pill content in the "Duo" layout, drawn from scratch:
 * left of the camera the battery number; right of it one combined glyph —
 * a ring whose filled length is the battery, Wi-Fi inside, four dots in the ring's gap for mobile signal.
 */
class StatusView(context: Context, private val h: Int, private val gap: Int, font: Typeface, private val showNumber: Boolean) : View(context) {
    private val dim = 0x47FFFFFF
    private val white = 0xFFFFFFFF.toInt()
    private val ringBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = dim }
    private val ringFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; color = white; textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum" }
    private val r = RectF()

    /** Ring diameter. */
    private val ring get() = h * 0.80f
    val leftW get() = if (showNumber) { num.textSize = h * 0.44f; num.measureText("100") } else 0f
    val rightW get() = ring

    override fun onDraw(c: Canvas) {
        val s = StatusState
        val cy = height / 2f
        if (showNumber) {
            num.textSize = h * 0.44f
            num.color = batteryColor(s.battery, s.charging, forText = true)
            val cx = width / 2f - gap / 2f - leftW / 2
            c.drawText("${s.battery}", cx, cy - (num.descent() + num.ascent()) / 2, num)
        }
        drawDuo(c, width / 2f + gap / 2f + ring / 2, cy, s)
    }

    private fun batteryColor(level: Int, charging: Boolean, forText: Boolean = false) = when {
        charging -> Ui.GREEN
        level <= 20 -> Ui.RED
        else -> if (forText) white else white
    }

    private fun drawDuo(c: Canvas, cx: Float, cy: Float, s: StatusState) {
        val d = ring
        val stroke = d * 0.10f
        val rad = d / 2 - stroke / 2
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        // Ring open at the bottom (start 135°, 270° sweep); filled part = battery.
        ringBg.strokeWidth = stroke; ringFg.strokeWidth = stroke
        c.drawArc(r, 135f, 270f, false, ringBg)
        ringFg.color = batteryColor(s.battery, s.charging)
        c.drawArc(r, 135f, 270f * (s.battery.coerceIn(0, 100) / 100f), false, ringFg)

        // Wi-Fi fan inside the ring (dimmed parts = weaker signal; hidden when Wi-Fi is off).
        if (s.wifi >= 0) {
            // Kept well inside the ring: outer arc radius is about half the ring's inner radius.
            val base = cy + d * 0.11f
            val st = d * 0.07f
            arc.strokeWidth = st
            dot.color = white
            c.drawCircle(cx, base, st * 0.8f, dot)
            for (k in 1..2) {
                val ar = d * (0.08f + 0.085f * k)
                r.set(cx - ar, base - ar, cx + ar, base + ar)
                arc.color = if (s.wifi >= k + 1) white else dim
                c.drawArc(r, 225f, 90f, false, arc)
            }
        }

        // Four dots in the ring's bottom gap: mobile signal.
        val dr = d * 0.05f
        val spread = d * 0.15f
        val yBase = cy + d * 0.41f
        for (k in 0 until 4) {
            val off = (k - 1.5f) * spread
            val y = yBase + (if (k == 0 || k == 3) -d * 0.04f else 0f)
            dot.color = if (s.cell > k) white else dim
            c.drawCircle(cx + off, y, dr, dot)
        }
    }
}
