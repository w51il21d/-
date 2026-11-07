package com.w51il21d.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
import kotlin.math.min

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
    private lateinit var tlp: WindowManager.LayoutParams
    private var touchView: View? = null
    private var probe: View? = null
    private var events: SystemEvents? = null
    var statusVisible = true
        private set
    var lastError: String? = null
        private set
    val islandMode get() = if (::island.isInitialized) island.mode else null
    val islandSuppressed get() = ::island.isInitialized && island.suppressed
    val islandRect get() = if (::island.isInitialized) island.lastPlace else null
    val islandGeo get() = if (::island.isInitialized) island.geo else null
    val windowAttached get() = ::island.isInitialized && island.parent != null
    private val hubListener = { island.show(Hub.current()) }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onPref(key) }

    override fun onServiceConnected() {
        if (::island.isInitialized) { ensureWindows(); return }
        instance = this
        EventLog.add("서비스 연결됨")
        wm = getSystemService(WindowManager::class.java)
        lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "Island"
        }
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        tlp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "IslandTouch"
        }
        island = IslandView(this, geometry(), object : IslandView.Host {
            override fun frame(h: Int) {
                lp.height = h
                if (island.isAttachedToWindow) try { wm.updateViewLayout(island, lp) } catch (e: Exception) { lastError = "창 갱신 실패: ${e.message}" }
            }
            override fun touch(x: Int, y: Int, w: Int, h: Int, touchable: Boolean) {
                tlp.x = x; tlp.y = y; tlp.width = w; tlp.height = h
                tlp.flags = if (touchable) tlp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else tlp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                val t = touchView
                if (t != null && t.isAttachedToWindow) try { wm.updateViewLayout(t, tlp) } catch (e: Exception) { lastError = "터치 창 갱신 실패: ${e.message}" }
            }
        })
        island.onHold = { Hub.hold(it) }
        island.onDismissAlert = { Hub.dismissAlert() }
        applyPrefs()
        try { wm.addView(island, lp) } catch (e: Exception) { lastError = "창 추가 실패: ${e.message}" }
        addTouchWindow()
        addProbe()
        // Right after an app update the window can fail to appear; check again a few times.
        for (d in longArrayOf(1000, 3000, 6000, 12000)) handler.postDelayed(ensure, d)
        Hub.addListener(hubListener)
        island.show(Hub.current())
        updateSuppressed()
        events = SystemEvents(this).also { it.register() }
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        Restarter.markRunning(this, true)
        registerReceiver(screenRx, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, RECEIVER_EXPORTED)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ensure = Runnable { ensureWindows() }
    private val screenRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { handler.removeCallbacks(ensure); handler.postDelayed(ensure, 300) }
    }

    /** Re-attaches any window that is missing and re-reads the camera position. Safe to call any time. */
    fun ensureWindows() {
        if (instance !== this || !::island.isInitialized) return
        var reattached = false
        // parent is set synchronously by addView; isAttachedToWindow only after the next frame.
        if (island.parent == null) {
            try { wm.addView(island, lp); reattached = true; lastError = null; EventLog.add("창 다시 붙임") } catch (e: Exception) { lastError = "창 다시 붙이기 실패: ${e.message}" }
        }
        val t = touchView
        if (t == null || t.parent == null) {
            t?.let { try { wm.removeView(it) } catch (_: Exception) { } }
            touchView = null; addTouchWindow()
        }
        val p = probe
        if (p == null || p.parent == null) {
            p?.let { try { wm.removeView(it) } catch (_: Exception) { } }
            probe = null; addProbe()
        } else probeCheck()
        val g = geometry()
        if (g != island.geo) island.setGeometry(g) else if (reattached) island.refresh()
        updateSuppressed()
    }

    /** Called when the user turns the service off (not when the app is updated, which just kills it). */
    override fun onUnbind(intent: Intent?): Boolean {
        Restarter.markRunning(this, false)
        teardown()
        return super.onUnbind(intent)
    }

    /** Invisible window over the pill; hands its touches to the drawing window's views. */
    private fun addTouchWindow() {
        val v = object : View(this) {
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { island.collapse(); return true }
                val ev = MotionEvent.obtain(e)
                ev.offsetLocation(tlp.x.toFloat(), tlp.y.toFloat())
                island.dispatchTouchEvent(ev)
                ev.recycle()
                return true
            }
        }
        try { wm.addView(v, tlp); touchView = v } catch (e: Exception) { lastError = "터치 창 추가 실패: ${e.message}" }
    }

    /** True once the probe has ever been pushed below a visible status bar; until then we trust nothing. */
    var probeWorks = false
        private set

    /**
     * Full-height, 1-pixel-wide invisible window that does NOT draw under the status bar.
     * While the status bar shows, Android pushes it down (top > 0); in fullscreen apps it starts at 0.
     */
    private var probeCheck: () -> Unit = {}

    private fun addProbe() {
        val v = View(this)
        val check = {
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            if (loc[1] > 0) probeWorks = true
            val vis = !probeWorks || loc[1] > 0
            if (vis != statusVisible) { statusVisible = vis; updateSuppressed() }
        }
        probeCheck = check
        v.viewTreeObserver.addOnGlobalLayoutListener { check() }
        v.setOnApplyWindowInsetsListener { view, ins -> view.post { check() }; ins }
        val plp = WindowManager.LayoutParams(
            1, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = WindowInsets.Type.statusBars()
            title = "IslandProbe"
        }
        try { wm.addView(v, plp); probe = v } catch (e: Exception) { lastError = "감지 창 추가 실패: ${e.message}" }
    }

    private fun updateSuppressed() {
        if (!::island.isInitialized) return
        val g = island.geo
        island.setSuppressed((!statusVisible && Prefs.on(this, Prefs.HIDE_FULL)) || (g.landscape && Prefs.on(this, Prefs.HIDE_LAND)))
    }

    private fun applyPrefs() {
        island.offsetDp = Prefs.offset(this)
        island.scale = Prefs.scale(this) / 100f
        island.idlePill = Prefs.on(this, Prefs.IDLE)
        island.glowOn = Prefs.on(this, Prefs.GLOW)
    }

    private fun onPref(key: String?) {
        when (key) {
            Prefs.OFFSET, Prefs.SCALE -> { applyPrefs(); island.refresh() }
            Prefs.IDLE, Prefs.GLOW -> { applyPrefs(); island.show(Hub.current()) }
            Prefs.HIDE_LAND, Prefs.HIDE_FULL -> updateSuppressed()
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
            // Samsung reports the cutout from the top edge down to the lens, so the real diameter is the width.
            holeW = hole?.let { min(it.width(), it.height()) } ?: 0,
            holeH = hole?.let { min(it.width(), it.height()) } ?: 0,
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
        handler.removeCallbacks(ensure)
        try { unregisterReceiver(screenRx) } catch (_: Exception) { }
        events?.unregister(); events = null
        if (::island.isInitialized) {
            Hub.removeListener(hubListener)
            try { wm.removeView(island) } catch (_: Exception) { }
            touchView?.let { try { wm.removeView(it) } catch (_: Exception) { } }
            touchView = null
        }
        probe?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        probe = null
    }
}
