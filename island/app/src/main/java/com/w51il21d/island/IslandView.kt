package com.w51il21d.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the camera hole and status bar are, in pixels. */
data class Geo(
    val screenW: Int,
    val holeCx: Int,
    val holeCy: Int,
    val holeW: Int,
    val holeH: Int,
    val statusH: Int,
    val density: Float,
    val landscape: Boolean = false,
)

/**
 * The black pill. It is drawn in a screen-wide window that never moves sideways (moving a window
 * and redrawing it are not in sync, which made the pill jump). [Host.frame] only grows or shrinks
 * that window downward. Touches come through a separate invisible window, [Host.touch], sized to the pill;
 * window so that only the pill itself catches touches and the rest of the status bar stays usable.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class IslandView(context: Context, geo: Geo, private val host: Host) : FrameLayout(context) {
    interface Host {
        /** Height of the drawing window, which is fixed at the top-left and as wide as the screen. */
        fun frame(h: Int)
        /** Rectangle of the invisible touch window. */
        fun touch(x: Int, y: Int, w: Int, h: Int, touchable: Boolean)
    }
    enum class Mode { HIDDEN, IDLE, COMPACT, BANNER, EXPANDED }

    companion object {
        /** Tests switch animation off to render final states. */
        var animate = true
        private const val AUTO_COLLAPSE_MS = 7000L
    }

    var geo = geo; private set
    var offsetDp = 0
    var scale = 1f
    var idlePill = false
    var onHold: (Boolean) -> Unit = {}
    var onDismissAlert: () -> Unit = {}

    var mode = Mode.HIDDEN; private set
    var item: Item? = null; private set
    var suppressed = false; private set

    private val bg = GradientDrawable().apply { setColor(Color.BLACK) }
    private val pill = FrameLayout(context).apply { background = bg; clipToOutline = true; visibility = INVISIBLE }
    private var content: View? = null
    private var curW = 0
    private var curH = 0
    private var anim: ValueAnimator? = null

    private var seek: SeekBar? = null
    private var posLabel: TextView? = null
    private var remainLabel: TextView? = null
    private var seeking = false

    private val h = Handler(Looper.getMainLooper())
    private val autoCollapse = Runnable { if (mode == Mode.EXPANDED) collapse() }
    private val tick = object : Runnable {
        override fun run() {
            updateMediaProgress()
            if (mode == Mode.EXPANDED && item?.media != null) h.postDelayed(this, 500)
        }
    }

    init {
        clipChildren = false
        addView(pill, LayoutParams(1, 1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (mode == Mode.EXPANDED) open() else expand()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                open()
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vy) < abs(vx)) return false
                if (vy < 0) {
                    if (mode == Mode.EXPANDED) collapse()
                    else if (item?.kind == Kind.ALERT) onDismissAlert()
                } else if (mode != Mode.EXPANDED) expand()
                return true
            }
        })
        pill.pivotY = 0f
        pill.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pill.pivotX = pill.width / 2f; pill.animate().scaleX(0.95f).scaleY(0.95f).setDuration(110).setInterpolator(DecelerateInterpolator()).start() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pill.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
            }
            gd.onTouchEvent(e)
            if (mode == Mode.EXPANDED) restartAutoCollapse()
            true
        }
    }

    // ---------------------------------------------------------------- public

    fun show(next: Item?) {
        val prev = item
        item = next
        val target = when {
            next == null -> if (idlePill) Mode.IDLE else Mode.HIDDEN
            mode == Mode.EXPANDED && prev?.key == next.key -> Mode.EXPANDED
            else -> restingMode(next)
        }
        if (mode == Mode.EXPANDED && target != Mode.EXPANDED) { h.removeCallbacks(autoCollapse); onHold(false) }
        transition(target, keyChanged = prev?.key != next?.key)
    }

    fun expand() {
        if (item == null || mode == Mode.EXPANDED) return
        onHold(true)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        transition(Mode.EXPANDED, keyChanged = false)
        restartAutoCollapse()
    }

    fun collapse() {
        h.removeCallbacks(autoCollapse)
        if (mode != Mode.EXPANDED) return
        onHold(false)
        transition(item?.let { restingMode(it) } ?: Mode.HIDDEN, keyChanged = false)
    }

    fun setSuppressed(s: Boolean) {
        if (s == suppressed) return
        suppressed = s
        if (s && mode == Mode.EXPANDED) collapse()
        alpha = if (s) 0f else 1f
        place(curW, curH)
    }

    fun setGeometry(g: Geo) {
        geo = g
        refresh()
    }

    /** Re-lays out after a size or position setting changed. */
    fun refresh() {
        transition(mode, keyChanged = false, animated = false)
    }

    /** Last window rectangle given to the host: x, y, w, h. For tests. */
    var lastPlace = intArrayOf(0, 0, 0, 0); private set
    /** Last drawing-window height. For tests. */
    var lastFrameH = 0; private set

    // ---------------------------------------------------------------- geometry

    private fun raw(dp: Float) = (dp * geo.density).roundToInt()
    private fun sp(v: Float) = v * geo.density
    /** Pill height: fits inside the status bar (at 100% size) while still covering the camera. */
    private val compactH: Int get() {
        val d = geo.holeW
        val want = if (d > 0) (d * 1.35f * scale).roundToInt() else (geo.statusH * 0.70f * scale).roundToInt()
        val h = max(want, (d * 1.08f).roundToInt())
        return if (scale <= 1f) min(h, geo.statusH - raw(2f)) else h
    }
    private val wideW get() = geo.screenW - raw(20f)
    /** Centred on the camera, kept inside the status bar band, then the user's fine offset. */
    private val topY: Int get() {
        val cy = if (geo.holeH > 0 && geo.holeCy < geo.statusH) geo.holeCy else geo.statusH / 2
        val y = (cy - compactH / 2).coerceIn(0, max(0, geo.statusH - compactH))
        return max(0, y + raw(offsetDp.toFloat()))
    }
    private val compactText get() = compactH * 0.46f
    private val compactIcon get() = (compactH * 0.70f).roundToInt()

    private fun restingMode(i: Item) = if (i.shape == Shape.BANNER) Mode.BANNER else Mode.COMPACT

    private fun radiusFor(h: Int) = min(h / 2f, compactH / 2f + raw(14f))

    private fun place(w: Int, h: Int) = placeAnim(h, w, h)

    /** Drawing window tall enough for [frameH] of pill; touch window exactly on the target pill. */
    private fun placeAnim(frameH: Int, w: Int, h: Int) {
        val touch = !suppressed && mode != Mode.HIDDEN && mode != Mode.IDLE
        val x = geo.holeCx - w / 2
        lastPlace = intArrayOf(x, topY, w, h)
        val fh = topY + max(1, frameH)
        if (fh != lastFrameH) { lastFrameH = fh; host.frame(fh) }
        host.touch(x, topY, max(1, w), max(1, h), touch)
    }

    private fun applySize(w: Int, h: Int) {
        curW = w; curH = h
        val lp = pill.layoutParams as LayoutParams
        lp.width = max(1, w); lp.height = max(1, h); lp.topMargin = topY
        pill.layoutParams = lp
        pill.translationX = geo.holeCx - geo.screenW / 2f
        bg.cornerRadius = radiusFor(h)
    }

    // ---------------------------------------------------------------- transitions

    private fun transition(target: Mode, keyChanged: Boolean, animated: Boolean = animate) {
        h.removeCallbacks(tick)
        seek = null; posLabel = null; remainLabel = null
        val i = item
        val next: View? = when (target) {
            Mode.COMPACT -> i?.let { buildCompact(it) }
            Mode.BANNER -> i?.let { buildBanner(it) }
            Mode.EXPANDED -> i?.let { buildExpanded(it) }
            else -> null
        }
        val tw: Int
        val th: Int
        when (target) {
            Mode.HIDDEN -> { tw = compactH; th = compactH }
            Mode.IDLE -> { tw = geo.holeW + (compactH * 1.1f).roundToInt(); th = compactH }
            Mode.COMPACT -> { tw = compactWidth(i!!); th = compactH }
            Mode.BANNER -> { tw = wideW; th = compactH + raw(62f) }
            Mode.EXPANDED -> {
                tw = wideW
                next!!.measure(MeasureSpec.makeMeasureSpec(tw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                th = next.measuredHeight
            }
        }
        val wasVisible = pill.visibility == VISIBLE
        val fromW = if (wasVisible) curW else compactH
        val fromH = if (wasVisible) curH else compactH
        val modeChanged = target != mode
        mode = target

        anim?.cancel(); anim = null
        val old = content
        content = next
        val fade = animated && (modeChanged || keyChanged)
        if (next != null) {
            pill.addView(next, LayoutParams(tw, th, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            if (fade) enter(next, if (modeChanged) 110L else 40L)
        }
        if (old != null) {
            if (fade) old.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setStartDelay(0).setDuration(110).withEndAction { pill.removeView(old) }.start()
            else pill.removeView(old)
        }
        if (target != Mode.HIDDEN) pill.visibility = VISIBLE
        if (target == Mode.EXPANDED && i?.media != null) h.post(tick)

        if (!animated || (fromW == tw && fromH == th)) {
            applySize(tw, th); place(tw, th)
            if (target == Mode.HIDDEN) pill.visibility = INVISIBLE
            return
        }
        val grow = tw * th >= fromW * fromH
        // The spring overshoots by up to ~16% of the change; give the window room for it.
        val slackH = if (th > fromH) ((th - fromH) * 0.18f).roundToInt() + raw(2f) else 0
        placeAnim(max(fromH, th) + slackH, tw, th)
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (grow) 560 else 440
            interpolator = if (grow) Spring(5.2f, 9.5f) else Spring(7.5f, 8f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                applySize(fromW + ((tw - fromW) * f).roundToInt(), fromH + ((th - fromH) * f).roundToInt())
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    applySize(tw, th); place(tw, th)
                    if (target == Mode.HIDDEN) pill.visibility = INVISIBLE
                }
            })
            start()
        }
    }

    /** Damped spring: quick, with a small bounce, like a real island. */
    private class Spring(private val damping: Float, private val freq: Float) : android.view.animation.Interpolator {
        override fun getInterpolation(t: Float) = 1f - kotlin.math.exp(-damping * t) * kotlin.math.cos(freq * t)
    }

    /** Children rise in one after another. */
    private fun enter(v: View, delay: Long) {
        val kids = (v as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) } }?.takeIf { it.isNotEmpty() } ?: listOf(v)
        kids.forEachIndexed { idx, k ->
            k.alpha = 0f; k.translationY = raw(5f).toFloat(); k.scaleX = 0.9f; k.scaleY = 0.9f
            k.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(delay + idx * 45L).setDuration(280).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
    }

    /** A little gesture on the icon that says what happened. */
    private fun accent(icon: View, i: Item) {
        if (!animate) return
        val k = i.key
        val a: Animator = when {
            "charge" in k -> ObjectAnimator.ofPropertyValuesHolder(icon,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f, 1f, 1.25f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f, 1f, 1.25f, 1f)).setDuration(760)
            k == "ringer" || i.kind == Kind.CALL -> ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, -20f, 18f, -14f, 10f, -5f, 0f).setDuration(620)
            k == "lowbat" -> ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0.25f, 1f, 0.25f, 1f).setDuration(900)
            else -> ObjectAnimator.ofPropertyValuesHolder(icon,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.6f, 1.15f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.6f, 1.15f, 1f)).setDuration(420)
        }
        a.startDelay = 260
        a.start()
    }

    private fun restartAutoCollapse() {
        h.removeCallbacks(autoCollapse)
        h.postDelayed(autoCollapse, AUTO_COLLAPSE_MS)
    }

    private fun open() {
        val i = item ?: return
        i.open?.invoke()
        finishInteraction(i)
    }

    private fun finishInteraction(i: Item) {
        h.removeCallbacks(autoCollapse)
        if (i.kind == Kind.ALERT) { onHold(false); onDismissAlert() } else collapse()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { collapse(); return true }
        return super.onTouchEvent(e)
    }

    // ---------------------------------------------------------------- building blocks

    private fun tv(s: CharSequence?, sizePx: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        setTextColor(color)
        typeface = if (bold) Fonts.bold(context) else Fonts.regular(context)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun iconView(i: Item, size: Int): ImageView = ImageView(context).apply {
        setImageDrawable(i.icon ?: Ui.glyph(context, R.drawable.ic_notify, i.accent))
        if (i.iconPicture) {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    if (i.iconRound) o.setOval(0, 0, v.width, v.height)
                    else o.setRoundRect(0, 0, v.width, v.height, size * 0.24f)
                }
            }
            clipToOutline = true
        } else {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = (size * 0.08f).roundToInt(); setPadding(p, p, p, p)
        }
        layoutParams = ViewGroup.LayoutParams(size, size)
    }

    private fun chrono(i: Item, sizePx: Float, color: Int) = Chronometer(context).apply {
        base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - i.chronoBase!!)
        isCountDown = i.countDown
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        setTextColor(color)
        typeface = Fonts.bold(context)
        fontFeatureSettings = "tnum"
        includeFontPadding = false
        start()
    }

    private fun chronoSample(i: Item): String {
        val sec = abs(System.currentTimeMillis() - (i.chronoBase ?: 0)) / 1000
        return if (sec >= 3600) "0:00:00" else "00:00"
    }

    /** Right-hand view in the compact pill, or null. */
    private fun compactRight(i: Item): View? = when {
        i.media != null -> EqView(context, i.accent, i.media.playing)
        i.chronoBase != null -> chrono(i, compactText, i.accent)
        i.rightText != null -> tv(i.rightText, compactText, i.rightColor, bold = true)
        else -> null
    }

    private fun compactRightWidth(i: Item): Int {
        val p = Paint().apply { textSize = compactText; typeface = Fonts.bold(context) }
        return when {
            i.media != null -> (compactH * 0.62f).roundToInt()
            i.chronoBase != null -> p.measureText(chronoSample(i)).roundToInt()
            i.rightText != null -> min(p.measureText(i.rightText.toString()).roundToInt(), (geo.screenW * 0.30f).roundToInt())
            else -> 0
        }
    }

    private fun compactWidth(i: Item): Int {
        val pad = (compactH - compactIcon) / 2 + raw(3f)
        val side = max((compactH * 1.3f).roundToInt(), pad + max(compactIcon, compactRightWidth(i)) + raw(10f))
        return min(geo.holeW + raw(6f) + side * 2, wideW)
    }

    private fun buildCompact(i: Item): View {
        val f = FrameLayout(context)
        val pad = (compactH - compactIcon) / 2 + raw(3f)
        val ic = iconView(i, compactIcon)
        f.addView(ic, LayoutParams(compactIcon, compactIcon, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = pad })
        if (i.kind != Kind.MEDIA) accent(ic, i)
        compactRight(i)?.let { r ->
            val lp = if (r is EqView) LayoutParams((compactH * 0.62f).roundToInt(), (compactH * 0.5f).roundToInt(), Gravity.END or Gravity.CENTER_VERTICAL)
            else LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
            lp.marginEnd = pad + raw(2f)
            f.addView(r, lp)
        }
        f.contentDescription = listOfNotNull(i.title, i.rightText).joinToString(" ")
        return f
    }

    /** Row level with the camera: label left of the hole, optional note right of it. */
    private fun topRow(i: Item, right: CharSequence?, inset: Int): View {
        val f = FrameLayout(context)
        val half = (wideW - geo.holeW) / 2 - inset - raw(12f)
        val label = i.label ?: kindLabel(i.kind)
        f.addView(tv(label, sp(12.5f), Ui.GREY, bold = true).apply { maxWidth = half },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        if (right != null) f.addView(tv(right, sp(12.5f), Ui.GREY).apply { maxWidth = half },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        return f
    }

    private fun kindLabel(k: Kind) = when (k) {
        Kind.CALL -> "통화"; Kind.NAV -> "길 안내"; Kind.TIMER -> "타이머"; Kind.MEDIA -> "음악"; Kind.ALERT -> "알림"
    }

    private fun buildBanner(i: Item): View {
        val inset = raw(22f)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(inset, 0, inset, 0) }
        col.addView(topRow(i, "지금", 0), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, compactH))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val isz = raw(40f)
        val ic = iconView(i, isz)
        row.addView(ic, LinearLayout.LayoutParams(isz, isz).apply { marginEnd = raw(12f) })
        accent(ic, i)
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(i.title, sp(15f), Ui.WHITE, bold = true))
        if (!i.text.isNullOrBlank()) texts.addView(tv(i.text, sp(14f), Ui.GREY), LinearLayout.LayoutParams(-2, -2).apply { topMargin = raw(3f) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (i.rightText != null) row.addView(tv(i.rightText, sp(17f), i.rightColor, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = raw(10f) })
        col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, raw(52f)))
        return col
    }

    private fun buildExpanded(i: Item): View {
        val inset = raw(20f)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(inset, 0, inset, raw(16f)) }
        col.addView(topRow(i, null, 0), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, compactH))

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val isz = raw(if (i.media != null) 60f else 46f)
        row.addView(iconView(i, isz), LinearLayout.LayoutParams(isz, isz).apply { marginEnd = raw(14f) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(i.title, sp(16.5f), Ui.WHITE, bold = true, lines = 2))
        if (!i.text.isNullOrBlank()) texts.addView(tv(i.text, sp(14f), Ui.GREY, lines = if (i.media != null) 1 else 4),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = raw(4f) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val right: View? = when {
            i.media != null -> EqView(context, i.accent, i.media.playing).apply { layoutParams = LinearLayout.LayoutParams(raw(26f), raw(22f)) }
            i.chronoBase != null -> chrono(i, sp(26f), i.accent)
            i.rightText != null -> tv(i.rightText, sp(24f), i.rightColor, bold = true)
            else -> null
        }
        if (right != null) row.addView(right, (right.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-2, -2)).apply { marginStart = raw(12f) })
        col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = raw(2f) })

        i.media?.let { col.addView(mediaControls(it)) }
        if (i.actions.isNotEmpty()) col.addView(actionsRow(i), LinearLayout.LayoutParams(-1, -2).apply { topMargin = raw(14f) })
        return col
    }

    private fun mediaControls(m: MediaInfo): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (m.durationMs > 0) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val pos = tv("0:00", sp(12f), Ui.GREY).apply { fontFeatureSettings = "tnum" }
            val rem = tv("-0:00", sp(12f), Ui.GREY).apply { fontFeatureSettings = "tnum" }
            val sb = SeekBar(context).apply {
                max = 1000
                progressTintList = ColorStateList.valueOf(Ui.WHITE)
                progressBackgroundTintList = ColorStateList.valueOf(0xFF5A5A5E.toInt())
                thumbTintList = ColorStateList.valueOf(Ui.WHITE)
                splitTrack = false
                setPadding(raw(10f), 0, raw(10f), 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) pos.text = fmt(m.durationMs * p / 1000)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) { seeking = true; h.removeCallbacks(autoCollapse) }
                    override fun onStopTrackingTouch(s: SeekBar) {
                        seeking = false; m.seek(m.durationMs * s.progress / 1000); restartAutoCollapse()
                    }
                })
            }
            row.addView(pos, LinearLayout.LayoutParams(raw(40f), -2))
            row.addView(sb, LinearLayout.LayoutParams(0, raw(28f), 1f))
            row.addView(rem.apply { gravity = Gravity.END }, LinearLayout.LayoutParams(raw(44f), -2))
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = raw(12f) })
            seek = sb; posLabel = pos; remainLabel = rem
            updateMediaProgress()
        }
        val ctl = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun btn(res: Int, size: Float, desc: String, run: () -> Unit) = ImageView(context).apply {
            setImageDrawable(Ui.glyph(context, res, Ui.WHITE))
            contentDescription = desc
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); run(); restartAutoCollapse() }
            ctl.addView(this, LinearLayout.LayoutParams(raw(size), raw(size)).apply { marginStart = raw(18f); marginEnd = raw(18f) })
        }
        btn(R.drawable.ic_prev, 38f, "이전 곡", m.prev)
        btn(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play, 46f, if (m.playing) "일시정지" else "재생", m.toggle)
        btn(R.drawable.ic_next, 38f, "다음 곡", m.next)
        col.addView(ctl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = raw(6f) })
        return col
    }

    private fun actionsRow(i: Item): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (a in i.actions.take(3)) {
            val color = when (a.style) { Act.Style.DANGER -> Ui.RED; Act.Style.CONFIRM -> Ui.GREEN; else -> Ui.DARK }
            val b = tv(a.label, sp(14.5f), Ui.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(color, raw(21f).toFloat())
                setPadding(raw(8f), 0, raw(8f), 0)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    a.run()
                    finishInteraction(i)
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, raw(42f), 1f).apply { marginStart = raw(4f); marginEnd = raw(4f) })
        }
        return row
    }

    private fun updateMediaProgress() {
        val m = item?.media ?: return
        val sb = seek ?: return
        if (m.durationMs <= 0) return
        val p = m.positionMs().coerceIn(0, m.durationMs)
        if (!seeking) { sb.progress = (p * 1000 / m.durationMs).toInt(); posLabel?.text = fmt(p) }
        remainLabel?.text = "-" + fmt(m.durationMs - p)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
