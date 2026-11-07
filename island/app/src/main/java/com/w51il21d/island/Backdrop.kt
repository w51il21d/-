package com.w51il21d.island

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Stretched, softly blurred album picture with its own rounded corners (never spills past the pill). */
class Backdrop(private val bitmap: Bitmap, private val radius: Float, private val alphaValue: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = alphaValue }
    private val path = Path()
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        rect.set(b)
        path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.save(); c.clipPath(path)
        c.drawBitmap(bitmap, null, rect, paint)
        c.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha * alphaValue / 255; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
