package com.w51il21d.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
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
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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
 * that window downward. Touches come through a separate invisible window, [Host.touch], sized to the pill.
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
        private const val SECONDARY = 0xFFA3A3A8.toInt()
        private const val BUTTON = 0xFF2C2C2F.toInt()
        private const val DANGER = 0xFFF2453D.toInt()
        private const val CONFIRM = 0xFF34C759.toInt()
        private val ROLL_TAGS = listOf("right", "title", "text", "art")
    }

    var geo = geo; private set
    var offsetDp = 0
    var scale = 1f
    var idlePill = false
    var glowOn = true
    var onHold: (Boolean) -> Unit = {}
    var onDismissAlert: () -> Unit = {}

    var mode = Mode.HIDDEN; private set
    var item: Item? = null; private set
    var suppressed = false; private set

    private val bg = GradientDrawable().apply { setColor(Color.BLACK) }
    private val pill = FrameLayout(context).apply { background = bg; clipToOutline = true; visibility = INVISIBLE }
    private val glow = GlowView(context, pill)
    private var content: View? = null
    private var curW = 0
    private var curH = 0
    private var anim: ValueAnimator? = null

    private var seek: SlimSeek? = null
    private var posLabel: TextView? = null
    private var remainLabel: TextView? = null

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
        addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
                    flick()
                    if (mode == Mode.EXPANDED) collapse()
                    else if (item?.kind == Kind.ALERT) onDismissAlert()
                } else if (mode != Mode.EXPANDED) expand()
                return true
            }
        })
        pill.pivotY = 0f
        pill.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pill.pivotX = pill.width / 2f
                    pill.animate().scaleX(0.95f).scaleY(0.95f).setDuration(110).setInterpolator(DecelerateInterpolator()).setUpdateListener { glow.invalidate() }.start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    pill.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3f)).setUpdateListener { glow.invalidate() }.start()
            }
            gd.onTouchEvent(e)
            if (mode == Mode.EXPANDED) restartAutoCollapse()
            true
        }
    }

    // ---------------------------------------------------------------- public

    fun show(next: Item?) {
        val prev = item
        // Don't rebuild under the user's finger while they drag the music position.
        if (seek?.dragging == true && next != null && prev?.key == next.key) { item = next; return }
        item = next
        val target = when {
            next == null -> if (idlePill) Mode.IDLE else Mode.HIDDEN
            mode == Mode.EXPANDED && prev?.key == next.key -> Mode.EXPANDED
            else -> restingMode(next)
        }
        if (mode == Mode.EXPANDED && target != Mode.EXPANDED) { h.removeCallbacks(autoCollapse); onHold(false) }
        val keyChanged = prev?.key != next?.key
        if (keyChanged || target != mode) EventLog.add("모양: $mode → $target${if (next != null) " (${next.label ?: next.title})" else ""}${if (suppressed) " · 숨김 중" else ""}")
        transition(target, keyChanged, leaving = if (keyChanged) prev else null)
        if (keyChanged && next != null) arrivalGlow(next)
        updateBreathing()
    }

    fun expand() {
        if (item == null || mode == Mode.EXPANDED) return
        onHold(true)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        transition(Mode.EXPANDED, keyChanged = false)
        updateBreathing()
        restartAutoCollapse()
    }

    fun collapse() {
        h.removeCallbacks(autoCollapse)
        if (mode != Mode.EXPANDED) return
        onHold(false)
        transition(item?.let { restingMode(it) } ?: Mode.HIDDEN, keyChanged = false)
        updateBreathing()
    }

    fun setSuppressed(s: Boolean) {
        if (s == suppressed) return
        suppressed = s
        EventLog.add(if (s) "아일랜드 숨김 (전체화면/가로)" else "아일랜드 다시 보임")
        if (s && mode == Mode.EXPANDED) collapse()
        // Invisible (not just transparent) so music bars and glow stop redrawing while hidden.
        visibility = if (s) INVISIBLE else VISIBLE
        place(curW, curH)
        updateBreathing()
    }

    fun setGeometry(g: Geo) {
        geo = g
        refresh()
    }

    /** Re-lays out after a size or position setting changed. */
    fun refresh() {
        transition(mode, keyChanged = false, animated = false)
        updateBreathing()
    }

    /** Last touch rectangle given to the host: x, y, w, h. For tests. */
    var lastPlace = intArrayOf(0, 0, 0, 0); private set
    /** Last drawing-window height. For tests. */
    var lastFrameH = 0; private set

    // ---------------------------------------------------------------- geometry

    private fun raw(dp: Float) = (dp * geo.density).roundToInt()
    private fun sp(v: Float) = v * geo.density
    private val glowPad get() = raw(14f)

    /** Pill height from the real camera lens, fitting inside the status bar at 100%. */
    private val compactH: Int get() {
        val d = geo.holeW
        val want = if (d > 0) (d * 1.35f * scale).roundToInt() else (geo.statusH * 0.70f * scale).roundToInt()
        val hh = max(want, (d * 1.08f).roundToInt())
        return if (scale <= 1f) min(hh, geo.statusH - raw(2f)) else hh
    }
    private val wideW get() = geo.screenW - raw(20f)
    /** Centred on the camera, kept inside the status bar band, then the user's fine offset. */
    private val topY: Int get() {
        val cy = if (geo.holeH > 0 && geo.holeCy < geo.statusH) geo.holeCy else geo.statusH / 2
        val y = (cy - compactH / 2).coerceIn(0, max(0, geo.statusH - compactH))
        return max(0, y + raw(offsetDp.toFloat()))
    }
    private val compactText get() = compactH * 0.44f
    private val compactIcon get() = (compactH * 0.62f).roundToInt()
    /** Inset that makes a round icon sit concentric with the pill's rounded end. */
    private val compactPad get() = (compactH - compactIcon) / 2

    private fun restingMode(i: Item) = if (i.shape == Shape.BANNER) Mode.BANNER else Mode.COMPACT

    private fun radiusFor(hh: Int) = min(hh / 2f, compactH / 2f + raw(16f))

    private fun place(w: Int, hh: Int) = placeAnim(hh, w, hh)

    /** Drawing window tall enough for [frameH] of pill plus glow; touch window exactly on the target pill. */
    private fun placeAnim(frameH: Int, w: Int, hh: Int) {
        val touch = !suppressed && mode != Mode.HIDDEN && mode != Mode.IDLE
        val x = geo.holeCx - w / 2
        lastPlace = intArrayOf(x, topY, w, hh)
        val fh = topY + max(1, frameH) + glowPad
        if (fh != lastFrameH) { lastFrameH = fh; host.frame(fh) }
        host.touch(x, topY, max(1, w), max(1, hh), touch)
    }

    private fun applySize(w: Int, hh: Int) {
        curW = w; curH = hh
        val lp = pill.layoutParams as LayoutParams
        lp.width = max(1, w); lp.height = max(1, hh); lp.topMargin = topY
        pill.layoutParams = lp
        pill.translationX = geo.holeCx - geo.screenW / 2f
        val r = radiusFor(hh)
        bg.cornerRadius = r
        pill.invalidateOutline()
        glow.corner = r
        glow.invalidate()
    }

    // ---------------------------------------------------------------- transitions

    /** Damped spring on 0..1 time: quick, with a small bounce. */
    private fun spring(t: Float, damping: Float, freq: Float) =
        if (t <= 0f) 0f else if (t >= 1f) 1f else 1f - exp(-damping * t) * cos(freq * t)

    private fun transition(target: Mode, keyChanged: Boolean, animated: Boolean = animate, leaving: Item? = null) {
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
            Mode.BANNER -> {
                tw = wideW
                next!!.measure(MeasureSpec.makeMeasureSpec(tw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                th = next.measuredHeight
            }
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
            if (fade) enter(next, if (modeChanged) 110L else 60L)
        }
        if (old != null) when {
            fade -> exit(old, alertGone = leaving?.kind == Kind.ALERT)
            animated && next != null -> morph(old, next)
            else -> pill.removeView(old)
        }
        if (target != Mode.HIDDEN) pill.visibility = VISIBLE
        if (target == Mode.EXPANDED && i?.media != null) h.post(tick)

        if (!animated || (fromW == tw && fromH == th)) {
            applySize(tw, th); place(tw, th)
            if (target == Mode.HIDDEN) { pill.visibility = INVISIBLE; glow.stop() }
            return
        }
        val grow = tw * th >= fromW * fromH
        // The spring overshoots by up to ~16% of the change; give the window room for it.
        val slackH = if (th > fromH) ((th - fromH) * 0.18f).roundToInt() + raw(2f) else 0
        placeAnim(max(fromH, th) + slackH, tw, th)
        // Jelly: when growing, width leads and height follows; when shrinking, height goes first.
        val lag = 0.14f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (grow) 620 else 480
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val tW = if (grow) t / (1 - lag) else (t - lag) / (1 - lag)
                val tH = if (grow) (t - lag) / (1 - lag) else t / (1 - lag)
                val (d, f) = if (grow) 5.0f to 9.5f else 7.0f to 8.5f
                val fw = spring(tW, d, f)
                val fh = spring(tH, d, f)
                applySize(fromW + ((tw - fromW) * fw).roundToInt(), fromH + ((th - fromH) * fh).roundToInt())
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    applySize(tw, th); place(tw, th)
                    if (target == Mode.HIDDEN) { pill.visibility = INVISIBLE; glow.stop() }
                }
            })
            start()
        }
    }

    /** Children rise in one after another. */
    private fun enter(v: View, delay: Long) {
        val kids = (v as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) } }?.takeIf { it.isNotEmpty() } ?: listOf(v)
        kids.forEachIndexed { idx, k ->
            k.alpha = 0f; k.translationY = raw(6f).toFloat(); k.scaleX = 0.9f; k.scaleY = 0.9f
            k.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(delay + idx * 45L).setDuration(300).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
    }

    /** Old content leaves; a finished alert floats up and dissolves. */
    private fun exit(old: View, alertGone: Boolean) {
        val a = old.animate().alpha(0f).setStartDelay(0).withEndAction { pill.removeView(old) }
        if (alertGone) a.translationY(-raw(10f).toFloat()).scaleX(0.86f).scaleY(0.86f).setDuration(220).setInterpolator(DecelerateInterpolator())
        else a.scaleX(0.94f).scaleY(0.94f).setDuration(120)
        a.start()
        if (alertGone && animate) {
            // A little settle wobble once the pill has shrunk back.
            h.postDelayed({
                pill.pivotX = pill.width / 2f
                ObjectAnimator.ofPropertyValuesHolder(pill,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f, 0.98f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.94f, 1.02f, 1f)).apply {
                    duration = 420
                    addUpdateListener { glow.invalidate() }
                }.start()
            }, 360)
        }
    }

    /**
     * Same item, new values (track changed, % changed, distance changed): the parts that changed
     * roll — old text slides up and out, new text comes up from below. Everything else swaps instantly.
     */
    private fun morph(old: View, next: View) {
        val rolled = mutableListOf<View>()
        for (tag in ROLL_TAGS) {
            val o = old.findViewWithTag<View>(tag) ?: continue
            val n = next.findViewWithTag<View>(tag) ?: continue
            if (sameContent(o, n)) continue
            val dist = max(o.height, raw(14f)).toFloat() * 0.8f
            n.translationY = dist; n.alpha = 0f
            n.animate().translationY(0f).alpha(1f).setDuration(320).setStartDelay(40).setInterpolator(DecelerateInterpolator(2f)).start()
            rolled += o
        }
        if (rolled.isEmpty()) { pill.removeView(old); return }
        // Keep only the changing parts of the old content visible while they roll away.
        hideAllBut(old, rolled.toSet())
        for (o in rolled) o.animate().translationY(-max(o.height, raw(14f)) * 0.8f).alpha(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        h.postDelayed({ pill.removeView(old) }, 300)
    }

    private fun sameContent(a: View, b: View): Boolean = when {
        a is TextView && b is TextView -> a.text?.toString() == b.text?.toString()
        a is ImageView && b is ImageView -> a.drawable?.constantState == b.drawable?.constantState
        else -> true
    }

    private fun hideAllBut(v: View, keep: Set<View>): Boolean {
        if (v in keep) return true
        if (v is ViewGroup) {
            var any = false
            for (k in 0 until v.childCount) if (hideAllBut(v.getChildAt(k), keep)) any = true
            if (any) { v.background = null; return true }
        }
        v.visibility = INVISIBLE
        return false
    }

    /** A quick jump when the island is flicked away. */
    private fun flick() {
        if (!animate) return
        pill.animate().translationY(-raw(5f).toFloat()).setDuration(90).withEndAction {
            pill.animate().translationY(0f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
        }.setUpdateListener { glow.invalidate() }.start()
    }

    // ---------------------------------------------------------------- light

    private fun arrivalGlow(i: Item) {
        if (!animate || !glowOn) return
        val k = i.key
        when {
            "charge" in k -> glow.play(GlowView.Effect.SWEEP, Ui.GREEN)
            i.kind == Kind.ALERT -> glow.play(GlowView.Effect.FLASH, if (i.iconPicture) Ui.accentOf(i.icon) else i.accent)
            i.kind == Kind.CALL -> glow.play(GlowView.Effect.FLASH, Ui.GREEN)
        }
    }

    /** Music playing → the album colour breathes softly around the pill. */
    private fun updateBreathing() {
        val i = item
        val breathe = animate && glowOn && !suppressed && i?.media?.playing == true && mode != Mode.HIDDEN
        if (breathe) {
            if (glow.playing == GlowView.Effect.NONE || glow.playing == GlowView.Effect.BREATHE) glow.play(GlowView.Effect.BREATHE, i!!.accent)
        } else if (glow.playing == GlowView.Effect.BREATHE) glow.stop()
    }

    // ---------------------------------------------------------------- interaction

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

    // ---------------------------------------------------------------- building blocks

    private fun tv(s: CharSequence?, sizePx: Float, color: Int, weight: Int = 400, lines: Int = 1, tag: String? = null) = TextView(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        setTextColor(color)
        typeface = when { weight >= 700 -> Fonts.bold(context); weight >= 600 -> Fonts.semibold(context); else -> Fonts.regular(context) }
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        if (tag != null) this.tag = tag
    }

    private fun iconView(i: Item, size: Int, corner: Float = 0.24f): ImageView = ImageView(context).apply {
        setImageDrawable(i.icon ?: Ui.glyph(context, R.drawable.ic_notify, i.accent))
        tag = "art"
        if (i.iconPicture) {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    if (i.iconRound) o.setOval(0, 0, v.width, v.height)
                    else o.setRoundRect(0, 0, v.width, v.height, size * corner)
                }
            }
            clipToOutline = true
        } else {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = (size * 0.06f).roundToInt(); setPadding(p, p, p, p)
        }
    }

    private fun smallAppIcon(i: Item, size: Int): View? {
        val d: Drawable = Ui.appIcon(context, i.pkg) ?: return null
        return ImageView(context).apply {
            setImageDrawable(d)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, size * 0.28f) }
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = raw(6f) }
        }
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

    private fun eqSize() = (compactH * 0.50f).roundToInt() to (compactH * 0.40f).roundToInt()

    /** Right-hand view in the compact pill, or null. */
    private fun compactRight(i: Item): View? = when {
        i.media != null -> EqView(context, i.accent, i.media.playing)
        i.chronoBase != null -> chrono(i, compactText, i.accent)
        i.rightText != null -> tv(i.rightText, compactText, i.rightColor, weight = 700, tag = "right").apply { fontFeatureSettings = "tnum" }
        else -> null
    }

    private fun compactRightWidth(i: Item): Int {
        val p = Paint().apply { textSize = compactText; typeface = Fonts.bold(context); fontFeatureSettings = "tnum" }
        return when {
            i.media != null -> eqSize().first
            i.chronoBase != null -> p.measureText(chronoSample(i)).roundToInt()
            i.rightText != null -> min(p.measureText(i.rightText.toString()).roundToInt(), (geo.screenW * 0.30f).roundToInt())
            else -> 0
        }
    }

    private fun compactWidth(i: Item): Int {
        val side = max((compactH * 1.25f).roundToInt(), compactPad * 2 + max(compactIcon, compactRightWidth(i)) + raw(6f))
        return min(geo.holeW + raw(8f) + side * 2, wideW)
    }

    private fun buildCompact(i: Item): View {
        val f = FrameLayout(context)
        val pad = compactPad + raw(2f)
        val ic = iconView(i, compactIcon, corner = 0.28f)
        f.addView(ic, LayoutParams(compactIcon, compactIcon, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = pad })
        if (i.kind != Kind.MEDIA) accent(ic, i)
        compactRight(i)?.let { r ->
            val lp = if (r is EqView) eqSize().let { (w, hh) -> LayoutParams(w, hh, Gravity.END or Gravity.CENTER_VERTICAL) }
            else LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
            lp.marginEnd = pad + raw(2f)
            f.addView(r, lp)
        }
        f.contentDescription = listOfNotNull(i.title, i.rightText).joinToString(" ")
        return f
    }

    /** Row level with the camera: small app icon + label left of the hole, optional note right of it. */
    private fun topRow(i: Item, right: CharSequence?): View {
        val f = FrameLayout(context)
        val half = (wideW - geo.holeW) / 2 - raw(34f)
        val left = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        smallAppIcon(i, raw(15f))?.let { left.addView(it) }
        left.addView(tv(i.label ?: kindLabel(i.kind), sp(12.5f), SECONDARY, weight = 600).apply { maxWidth = half })
        f.addView(left, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        if (right != null) f.addView(tv(right, sp(12.5f), SECONDARY).apply { maxWidth = half },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        return f
    }

    private fun kindLabel(k: Kind) = when (k) {
        Kind.CALL -> "통화"; Kind.NAV -> "길 안내"; Kind.TIMER -> "타이머"; Kind.MEDIA -> "음악"; Kind.ALERT -> "알림"
    }

    private fun column(top: Int = 0, bottom: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(raw(22f), top, raw(22f), bottom)
    }

    private fun mainRow(i: Item, iconSize: Int, titleSp: Float, textLines: Int, rightSp: Float): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ic = iconView(i, iconSize, corner = if (i.media != null) 0.2f else 0.26f)
        row.addView(ic, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = raw(14f) })
        if (i.kind != Kind.MEDIA) accent(ic, i)
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(i.title, sp(titleSp), Ui.WHITE, weight = 600, lines = if (i.media != null) 1 else 2, tag = "title"))
        if (!i.text.isNullOrBlank()) texts.addView(tv(i.text, sp(titleSp - 2.5f), SECONDARY, lines = textLines, tag = "text").apply { setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = raw(4f) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val right: View? = when {
            i.media != null -> EqView(context, i.accent, i.media.playing).apply { layoutParams = LinearLayout.LayoutParams(raw(22f), raw(18f)) }
            i.chronoBase != null -> chrono(i, sp(rightSp), i.accent)
            i.rightText != null -> tv(i.rightText, sp(rightSp), i.rightColor, weight = 700, tag = "right").apply { fontFeatureSettings = "tnum" }
            else -> null
        }
        if (right != null) row.addView(right, (right.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-2, -2)).apply { marginStart = raw(12f) })
        return row
    }

    private fun buildBanner(i: Item): View {
        val col = column(bottom = raw(14f))
        col.addView(topRow(i, "지금"), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, compactH))
        col.addView(mainRow(i, raw(42f), 15.5f, 1, 18f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = raw(6f) })
        return col
    }

    private fun buildExpanded(i: Item): View {
        i.media?.let { return buildPlayer(i, it) }
        val col = column(bottom = raw(18f))
        col.addView(topRow(i, if (i.kind == Kind.ALERT && i.shape == Shape.BANNER) "지금" else null),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, compactH))
        col.addView(mainRow(i, raw(46f), 16.5f, 4, 28f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = raw(6f) })
        if (i.actions.isNotEmpty()) col.addView(actionsRow(i), LinearLayout.LayoutParams(-1, -2).apply { topMargin = raw(16f) })
        return col
    }

    /**
     * Expanded music player: blurred album picture behind, art + title/artist/album, output device,
     * slim progress, round play button. Everything else stays black and quiet.
     */
    private fun buildPlayer(i: Item, m: MediaInfo): View {
        val col = column(bottom = raw(20f))
        val r = compactH / 2f + raw(16f)
        val scrim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(110, 0, 0, 0), Color.argb(200, 0, 0, 0), Color.argb(245, 0, 0, 0))).apply { cornerRadius = r }
        col.background = m.backdrop?.let { b ->
            android.graphics.drawable.LayerDrawable(arrayOf(
                Backdrop(b, r, 210),
                scrim))
        } ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(80, Color.red(i.accent), Color.green(i.accent), Color.blue(i.accent)), Color.TRANSPARENT)).apply { cornerRadius = r }

        col.addView(topRow(i, m.output?.let { "♫ $it" }), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, compactH))

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val isz = raw(72f)
        row.addView(iconView(i, isz, corner = 0.2f).apply { elevation = raw(6f).toFloat() }, LinearLayout.LayoutParams(isz, isz).apply { marginEnd = raw(16f) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(i.title, sp(18f), Ui.WHITE, weight = 600, tag = "title"))
        if (!i.text.isNullOrBlank()) texts.addView(tv(i.text, sp(14.5f), 0xD9FFFFFF.toInt(), tag = "text"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = raw(4f) })
        if (m.album != null) texts.addView(tv(m.album, sp(12.5f), 0x8CFFFFFF.toInt()), LinearLayout.LayoutParams(-2, -2).apply { topMargin = raw(3f) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(EqView(context, i.accent, m.playing), LinearLayout.LayoutParams(raw(22f), raw(18f)).apply { marginStart = raw(12f) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = raw(4f) })
        col.addView(mediaControls(m))
        return col
    }

    private fun mediaControls(m: MediaInfo): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (m.durationMs > 0) {
            val bar = SlimSeek(context)
            val times = FrameLayout(context)
            val pos = tv("0:00", sp(11.5f), SECONDARY).apply { fontFeatureSettings = "tnum" }
            val rem = tv("-0:00", sp(11.5f), SECONDARY).apply { fontFeatureSettings = "tnum" }
            times.addView(pos, LayoutParams(-2, -2, Gravity.START))
            times.addView(rem, LayoutParams(-2, -2, Gravity.END))
            bar.onSeekStart = { h.removeCallbacks(autoCollapse) }
            bar.onSeeking = { f -> pos.text = fmt((m.durationMs * f).toLong()) }
            bar.onSeekEnd = { f -> m.seek((m.durationMs * f).toLong()); restartAutoCollapse() }
            col.addView(bar, LinearLayout.LayoutParams(-1, raw(22f)).apply { topMargin = raw(14f) })
            col.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = raw(2f) })
            seek = bar; posLabel = pos; remainLabel = rem
            updateMediaProgress()
        }
        val ctl = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun btn(res: Int, size: Float, desc: String, primary: Boolean, run: () -> Unit) = ImageView(context).apply {
            setImageDrawable(Ui.glyph(context, res, if (primary) Color.BLACK else Ui.WHITE))
            contentDescription = desc
            if (primary) {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.WHITE) }
                val p = raw(12f); setPadding(p, p, p, p)
            } else { val p = raw(4f); setPadding(p, p, p, p) }
            setOnClickListener { v ->
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
                }.start()
                run(); restartAutoCollapse()
            }
            ctl.addView(this, LinearLayout.LayoutParams(raw(size), raw(size)).apply { marginStart = raw(22f); marginEnd = raw(22f) })
        }
        btn(R.drawable.ic_prev, 36f, "이전 곡", false, m.prev)
        btn(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play, 54f, if (m.playing) "일시정지" else "재생", true, m.toggle)
        btn(R.drawable.ic_next, 36f, "다음 곡", false, m.next)
        col.addView(ctl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = raw(8f) })
        return col
    }

    private fun actionsRow(i: Item): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (a in i.actions.take(3)) {
            val color = when (a.style) { Act.Style.DANGER -> DANGER; Act.Style.CONFIRM -> CONFIRM; else -> BUTTON }
            val b = tv(a.label, sp(14.5f), Ui.WHITE, weight = 600).apply {
                gravity = Gravity.CENTER
                background = Ui.round(color, raw(22f).toFloat())
                setPadding(raw(8f), 0, raw(8f), 0)
                setOnClickListener { v ->
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
                        a.run(); finishInteraction(i)
                    }.start()
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, raw(44f), 1f).apply { marginStart = raw(4f); marginEnd = raw(4f) })
        }
        return row
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

    private fun updateMediaProgress() {
        val m = item?.media ?: return
        val sb = seek ?: return
        if (m.durationMs <= 0) return
        val p = m.positionMs().coerceIn(0, m.durationMs)
        if (!sb.dragging) { sb.fraction = p.toFloat() / m.durationMs; posLabel?.text = fmt(p) }
        remainLabel?.text = "-" + fmt(m.durationMs - p)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
