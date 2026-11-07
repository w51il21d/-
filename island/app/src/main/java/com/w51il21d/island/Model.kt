package com.w51il21d.island

import android.graphics.drawable.Drawable

/** What the island is showing. Higher priority wins when several run at once. */
enum class Kind(val priority: Int) { CALL(100), NAV(80), TIMER(60), MEDIA(40), ALERT(0) }

/** Pill shape an item asks for when not expanded. */
enum class Shape { COMPACT, BANNER }

class Act(val label: CharSequence, val style: Style = Style.NORMAL, val run: () -> Unit) {
    enum class Style { NORMAL, DANGER, CONFIRM }
}

class MediaInfo(
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: () -> Long,
    val toggle: () -> Unit,
    val next: () -> Unit,
    val prev: () -> Unit,
    val seek: (Long) -> Unit,
)

data class Item(
    val key: String,
    val kind: Kind,
    val title: CharSequence,
    val text: CharSequence? = null,
    val pkg: String? = null,
    /** Small label in the top row next to the camera (app name or feature name). */
    val label: CharSequence? = null,
    val icon: Drawable? = null,
    /** true: crop to circle (avatars). false: rounded square (album art) or plain glyph. */
    val iconRound: Boolean = false,
    /** true when [icon] is a picture (art, avatar, app icon) and must not be tinted. */
    val iconPicture: Boolean = false,
    val accent: Int = 0xFFFFFFFF.toInt(),
    val rightText: CharSequence? = null,
    val rightColor: Int = 0xFFFFFFFF.toInt(),
    /** Wall-clock ms for a running clock (Notification.when). Null = no clock. */
    val chronoBase: Long? = null,
    val countDown: Boolean = false,
    val actions: List<Act> = emptyList(),
    val open: (() -> Unit)? = null,
    val media: MediaInfo? = null,
    val shape: Shape = Shape.COMPACT,
    /** How long a transient alert stays, in ms. Ignored for live items. */
    val durationMs: Long = 3000,
    val rank: Int = kind.priority,
    val seq: Long = 0,
)

object Selection {
    /** Highest rank wins; among equals, the most recently started one. */
    fun pick(items: Collection<Item>): Item? =
        items.maxWithOrNull(compareBy<Item>({ it.rank }, { it.seq }))
}
