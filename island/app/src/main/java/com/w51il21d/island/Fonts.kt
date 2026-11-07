package com.w51il21d.island

import android.content.Context
import android.graphics.Typeface

/** Pretendard (SIL Open Font License 1.1), bundled in res/font. */
object Fonts {
    private val cache = HashMap<Int, Typeface>()
    private fun load(c: Context, id: Int): Typeface = cache.getOrPut(id) {
        try { c.resources.getFont(id) } catch (_: Exception) { Typeface.DEFAULT }
    }
    fun regular(c: Context) = load(c, R.font.pretendard_regular)
    fun semibold(c: Context) = load(c, R.font.pretendard_semibold)
    fun bold(c: Context) = load(c, R.font.pretendard_bold)
}
