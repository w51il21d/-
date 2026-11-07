package com.w51il21d.island

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * Hosts the island window. An accessibility overlay can sit on top of the status bar and
 * survives in the background without a permanent notification.
 */
class IslandService : AccessibilityService() {
    companion object {
        var instance: IslandService? = null
            private set
        /** App currently on screen, used to skip previews for the chat you are already in. */
        @Volatile var foregroundPkg: String? = null
            private set
    }

    private lateinit var wm: WindowManager
    private lateinit var island: IslandView
    private lateinit var lp: WindowManager.LayoutParams
    private var probe: View? = null
    private var events: SystemEvents? = null
    private var statusVisible = true
    private val hubListener = { island.show(Hub.current()) }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onPref(key) }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WindowManager::class.java)
        lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "Island"
        }
        island = IslandView(this, geometry()) { x, y, w, h, touchable ->
            lp.x = x; lp.y = y; lp.width = w; lp.height = h
            lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (island.isAttachedToWindow) try { wm.updateViewLayout(island, lp) } catch (_: Exception) { }
        }
        island.onHold = { Hub.hold(it) }
        island.onDismissAlert = { Hub.dismissAlert() }
        applyPrefs()
        try { wm.addView(island, lp) } catch (_: Exception) { return }
        addProbe()
        Hub.addListener(hubListener)
        island.show(Hub.current())
        updateSuppressed()
        events = SystemEvents(this).also { it.register() }
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    /** 1×1 invisible window that tells us when an app hides the status bar (videos, games). */
    private fun addProbe() {
        val v = View(this)
        v.setOnApplyWindowInsetsListener { _, ins ->
            statusVisible = ins.isVisible(WindowInsets.Type.statusBars())
            updateSuppressed(); ins
        }
        v.viewTreeObserver.addOnGlobalLayoutListener {
            v.rootWindowInsets?.let { statusVisible = it.isVisible(WindowInsets.Type.statusBars()); updateSuppressed() }
        }
        val plp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; title = "IslandProbe" }
        try { wm.addView(v, plp); probe = v } catch (_: Exception) { }
    }

    private fun updateSuppressed() {
        if (!::island.isInitialized) return
        val g = island.geo
        island.setSuppressed(!statusVisible || (g.landscape && Prefs.on(this, Prefs.HIDE_LAND)))
    }

    private fun applyPrefs() {
        island.offsetDp = Prefs.offset(this)
        island.scale = Prefs.scale(this) / 100f
        island.idlePill = Prefs.on(this, Prefs.IDLE)
    }

    private fun onPref(key: String?) {
        when (key) {
            Prefs.OFFSET, Prefs.SCALE -> { applyPrefs(); island.refresh() }
            Prefs.IDLE -> { applyPrefs(); island.show(Hub.current()) }
            Prefs.HIDE_LAND -> updateSuppressed()
            Prefs.MEDIA -> NotifListener.instance?.refreshMedia()
            Prefs.ONGOING -> NotifListener.instance?.rescan()
        }
    }

    fun geometry(): Geo {
        val m = wm.maximumWindowMetrics
        val b = m.bounds
        val ins = m.windowInsets
        val d = resources.displayMetrics.density
        val sb = ins.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top.takeIf { it > 0 }
            ?: resources.getIdentifier("status_bar_height", "dimen", "android").takeIf { it != 0 }?.let { resources.getDimensionPixelSize(it) }
            ?: (24 * d).toInt()
        val rects: List<Rect> = ins.displayCutout?.boundingRects
            ?: getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.cutout?.boundingRects
            ?: emptyList()
        val hole = rects.filter { it.top <= sb && it.width() < b.width() / 2 && it.height() > 0 }
            .minByOrNull { abs(it.centerX() - b.width() / 2) }
        return Geo(
            screenW = b.width(),
            holeCx = hole?.centerX() ?: (b.width() / 2),
            holeCy = hole?.centerY() ?: (sb / 2),
            holeW = hole?.width() ?: 0,
            holeH = hole?.height() ?: 0,
            statusH = sb,
            density = d,
            landscape = b.width() > b.height(),
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::island.isInitialized) return
        island.post { island.setGeometry(geometry()); updateSuppressed() }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p != "com.android.systemui" && p != packageName) foregroundPkg = p
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance !== this) return
        instance = null
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        events?.unregister(); events = null
        if (::island.isInitialized) {
            Hub.removeListener(hubListener)
            try { wm.removeView(island) } catch (_: Exception) { }
        }
        probe?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        probe = null
    }
}
