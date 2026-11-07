package com.w51il21d.island

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import kotlin.math.roundToInt

object Ui {
    const val GREEN = 0xFF30D158.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val BLUE = 0xFF0A84FF.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()
    const val GREY = 0xFF9A9AA0.toInt()
    const val DARK = 0xFF2C2C2E.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density).roundToInt()

    fun glyph(c: Context, res: Int, tint: Int): Drawable = c.getDrawable(res)!!.mutate().apply { setTint(tint) }

    fun round(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    fun appIcon(c: Context, pkg: String?): Drawable? =
        pkg?.let { try { c.packageManager.getApplicationIcon(it) } catch (_: Exception) { null } }

    fun appLabel(c: Context, pkg: String?): CharSequence? = pkg?.let {
        try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(it, 0)) } catch (_: Exception) { null }
    }

    fun launch(c: Context, pkg: String?): Boolean {
        val i = pkg?.let { c.packageManager.getLaunchIntentForPackage(it) } ?: return false
        return try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    /** Sends a notification's intent. The accessibility service may start activities from the background. */
    @Suppress("DEPRECATION")
    fun fire(c: Context, pi: PendingIntent?): Boolean {
        pi ?: return false
        return try {
            val opts = if (Build.VERSION.SDK_INT >= 33) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle() else null
            pi.send(c, 0, null, null, null, null, opts); true
        } catch (_: Exception) { false }
    }

    /** Cheap content fingerprint, so a re-sent copy of the same album art counts as unchanged. */
    fun signature(b: Bitmap?): Long {
        b ?: return 0
        return try {
            val src = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
            val s = Bitmap.createScaledBitmap(src, 8, 8, true)
            var h = 1125899906842597L
            for (x in 0 until 8) for (y in 0 until 8) h = 31 * h + (s.getPixel(x, y) and 0xF0F0F0)
            h
        } catch (_: Exception) { b.hashCode().toLong() }
    }

    /** Very soft blur by shrinking to a few pixels; drawn stretched it becomes a colour wash. */
    fun backdrop(b: Bitmap?): Bitmap? {
        b ?: return null
        return try {
            val src = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
            val tiny = Bitmap.createScaledBitmap(src, 6, 6, true)
            Bitmap.createScaledBitmap(tiny, 48, 48, true)
        } catch (_: Exception) { null }
    }

    /** Main colour of an app icon or picture, for the glow. */
    fun accentOf(d: Drawable?): Int {
        d ?: return WHITE
        return try {
            val b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            val old = d.bounds; d.setBounds(0, 0, 24, 24); d.draw(c); d.bounds = old
            accentOf(b)
        } catch (_: Exception) { WHITE }
    }

    /** A lively colour from album art for the music bars; white if the art is grey. */
    fun accentOf(b: Bitmap?): Int {
        b ?: return WHITE
        return try {
            val src = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
            val s = Bitmap.createScaledBitmap(src, 12, 12, true)
            val hsv = FloatArray(3); var best = WHITE; var score = 0.25f
            for (x in 0 until 12) for (y in 0 until 12) {
                val p = s.getPixel(x, y); Color.colorToHSV(p, hsv)
                val sc = hsv[1] * hsv[2]
                if (hsv[2] > 0.45f && sc > score) { score = sc; best = p }
            }
            if (best == WHITE) WHITE else { Color.colorToHSV(best, hsv); hsv[2] = maxOf(hsv[2], 0.85f); Color.HSVToColor(hsv) }
        } catch (_: Exception) { WHITE }
    }
}
