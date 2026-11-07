package com.w51il21d.island

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

/**
 * Settings, laid out like a One UI settings page: big title area, rounded cards, rows with thin
 * dividers, sliders for size and position. Built in code (no XML layouts).
 */
class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private var diag: TextView? = null
    private val diagTick = object : Runnable {
        override fun run() { updateDiag(); root.postDelayed(this, 1000) }
    }
    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) runGrant()
        else toast("Shizuku에서 허용하지 않았습니다.")
    }

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val cardColor get() = if (dark) 0xFF171717.toInt() else 0xFFFCFCFC.toInt()
    private val pageColor get() = if (dark) 0xFF010101.toInt() else 0xFFF2F2F2.toInt()
    private val textColor get() = if (dark) 0xFFFAFAFA.toInt() else 0xFF0D0D0D.toInt()
    private val subColor get() = if (dark) 0xFF8E8E93.toInt() else 0xFF7A7A7A.toInt()
    private val divider get() = if (dark) 0xFF2A2A2A.toInt() else 0xFFE6E6E6.toInt()
    private val tonal get() = if (dark) 0xFF26282E.toInt() else 0xFFE8ECF6.toInt()
    private val accent = 0xFF3E91FF.toInt()

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(36f)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(pageColor); addView(root); fitsSystemWindows = true; isVerticalScrollBarEnabled = false }
        window.statusBarColor = pageColor
        window.navigationBarColor = pageColor
        setContentView(scroll)
        try { Shizuku.addRequestPermissionResultListener(shizukuResult) } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuResult) } catch (_: Throwable) { }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        build()
        root.removeCallbacks(diagTick); root.postDelayed(diagTick, 1000)
        IslandService.instance?.ensureWindows()
        if (IslandService.instance?.windowAttached != true) Restarter.healAsync(this, 0)
    }

    override fun onPause() { super.onPause(); root.removeCallbacks(diagTick) }

    // ---------------------------------------------------------------- state

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, IslandService::class.java)
        return s.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun notifOn(): Boolean =
        getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this, NotifListener::class.java))

    private fun btOn() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- page

    private fun build() {
        root.removeAllViews()
        header()

        val running = IslandService.instance != null
        if (!a11yOn() || !notifOn() || !btOn()) {
            section("권한")
            card {
                permRow(it, R.drawable.ic_pill, 0xFF1C1C1E.toInt(), "접근성: 아일랜드 표시", "아일랜드를 띄웁니다 (필수)", a11yOn()) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                permRow(it, R.drawable.ic_notify, 0xFFFF9500.toInt(), "알림 접근", "음악·통화·타이머·알림 (필수)", notifOn()) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                permRow(it, R.drawable.ic_headset, 0xFF0A84FF.toInt(), "근처 기기", "블루투스 이어폰 이름 표시 (선택)", btOn()) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
                }
            }
            if (!a11yOn() || !notifOn()) note("켜기 화면에서 스위치가 회색이면: [앱 정보 열기] → 오른쪽 위 ⋮ → [제한된 설정 허용] 후 다시 켜세요.", "앱 정보 열기") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        section("업데이트 후 자동 시작")
        card {
            val ok = Restarter.canWriteSecure(this)
            val sub = if (ok) "새 버전을 설치해도 아일랜드가 저절로 다시 켜집니다." else "새 버전을 설치하면 접근성이 끊길 수 있습니다. Shizuku로 한 번만 허용하면 저절로 다시 켭니다."
            permRow(it, R.drawable.ic_refresh, 0xFF5E5CE6.toInt(), "자동으로 다시 켜기", sub, ok) { askShizuku() }
        }

        section("미리보기")
        card { hero(it, running) }

        section("보여줄 내용")
        card {
            toggle(it, R.drawable.ic_music, 0xFFFF2D55.toInt(), Prefs.MEDIA, "음악 재생", "앨범 사진, 재생 막대, 재생 조작")
            toggle(it, R.drawable.ic_phone, 0xFF34C759.toInt(), Prefs.ONGOING, "통화 · 타이머 · 길 안내", "진행 중인 시간과 다음 안내")
            toggle(it, R.drawable.ic_notify, 0xFFFF9500.toInt(), Prefs.NOTIF, "알림 미리보기", "새 알림을 잠깐 펼쳐서 보여줌")
            toggle(it, R.drawable.ic_bolt, 0xFF30B94D.toInt(), Prefs.CHARGING, "충전", "충전기를 꽂을 때, 다 찼을 때")
            toggle(it, R.drawable.ic_battery_alert, 0xFFFF453A.toInt(), Prefs.LOWBAT, "배터리 부족", "20%, 10%, 5%에서 한 번씩")
            toggle(it, R.drawable.ic_volume, 0xFFAF52DE.toInt(), Prefs.RINGER, "소리 · 진동 · 무음 전환", null)
            toggle(it, R.drawable.ic_headset, 0xFF0A84FF.toInt(), Prefs.BT, "블루투스 이어폰 연결", "이어폰, 스피커, 차량", last = true)
        }

        section("효과와 동작")
        card {
            toggle(it, R.drawable.ic_bell, 0xFFFF9500.toInt(), Prefs.SHOW_QUIET, "조용한 알림도 표시", "앱이 소리 없이 보내는 알림도 띄움")
            toggle(it, R.drawable.ic_notify, 0xFF34C759.toInt(), Prefs.SHOW_FRONT, "보고 있는 앱 알림도 표시", "카톡을 보는 중에 온 카톡 알림도 띄움")
            toggle(it, R.drawable.ic_sparkle, 0xFFFFB800.toInt(), Prefs.GLOW, "빛 효과", "음악 색 번짐, 충전 테두리 빛, 알림 빛")
            toggle(it, R.drawable.ic_lock, 0xFF8E8E93.toInt(), Prefs.LOCK_PRIVATE, "잠금 화면 알림 내용 숨기기", "앱 이름과 '새 알림'만 표시")
            toggle(it, R.drawable.ic_fullscreen, 0xFF5AC8FA.toInt(), Prefs.HIDE_FULL, "전체화면에서 숨기기", "동영상·게임처럼 상태바가 사라질 때")
            toggle(it, R.drawable.ic_rotate, 0xFF64D2FF.toInt(), Prefs.HIDE_LAND, "가로 화면에서 숨기기", null)
            toggle(it, R.drawable.ic_pill, 0xFF3A3A3C.toInt(), Prefs.IDLE, "평소에도 작은 알약 표시", "표시할 내용이 없을 때도 카메라 둘레에", last = true)
        }

        section("크기와 위치")
        card {
            slider(it, "크기", Prefs.SCALE, 100, 60, 140, 5) { "$it%" }
            slider(it, "세로 위치", Prefs.OFFSET, 0, -12, 12, 1) { if (it == 0) "기본" else if (it > 0) "아래로 $it" else "위로 ${-it}" }
            slider(it, "알림 표시 시간", Prefs.NOTIF_SEC, 4, 2, 10, 1, last = true) { "${it}초" }
        }

        val apps = Prefs.seenApps(this)
        if (apps.isNotEmpty()) {
            section("알림 미리보기 앱")
            card {
                val ex = Prefs.excluded(this)
                apps.forEachIndexed { idx, pkg ->
                    val cb = CheckBox(this).apply {
                        text = Ui.appLabel(this@MainActivity, pkg) ?: pkg
                        setTextColor(textColor); textSize = 16.5f; typeface = Fonts.regular(context)
                        buttonTintList = ColorStateList.valueOf(accent)
                        isChecked = pkg !in ex
                        setOnCheckedChangeListener { _, on -> Prefs.setExcluded(this@MainActivity, pkg, !on) }
                    }
                    it.addView(cb, LinearLayout.LayoutParams(-1, dp(52f)))
                    if (idx < apps.size - 1) line(it, inset = false)
                }
            }
            note("체크를 풀면 그 앱의 알림은 아일랜드에 띄우지 않습니다.")
        }

        section("상태 확인")
        card {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12f), 0, dp(4f)) }
            r.addView(pill("아일랜드 창 다시 만들기") { IslandService.instance?.recreateWindows("직접 누름"); toast("다시 만들었습니다. 미리보기를 눌러 보세요.") }, LinearLayout.LayoutParams(-1, dp(44f)))
            it.addView(r)
            val t = text("", 13f, subColor).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(10f), 0, dp(10f)) }; diag = t; it.addView(t)
        }
        updateDiag()

        root.addView(text("아일랜드 ${packageManager.getPackageInfo(packageName, 0).versionName} · 글꼴 Pretendard (OFL)", 12f, subColor)
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(24f), 0, 0) })
    }

    private fun header() {
        val running = IslandService.instance != null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            minimumHeight = (resources.displayMetrics.heightPixels * 0.26f).toInt()
            setPadding(dp(12f), dp(24f), dp(12f), dp(12f))
        }
        box.addView(text("아일랜드", 34f, textColor, weight = 600).apply { gravity = Gravity.CENTER })
        box.addView(text(if (running) "켜져 있음 · 표시할 일이 생기면 카메라 둘레에 나타납니다" else "꺼져 있음 · 아래 권한을 켜 주세요",
            14f, if (running) 0xFF2EB85C.toInt() else subColor).apply { gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, 0) })
        root.addView(box, LinearLayout.LayoutParams(-1, -2))
    }

    /** A small phone-top scene with the island on it; the buttons play real previews on the phone. */
    private fun hero(parent: LinearLayout, running: Boolean) {
        val scene = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF7F9CF5.toInt(), 0xFFC6A4E0.toInt(), 0xFFF6AD8F.toInt())).apply { cornerRadius = dp(20f).toFloat() }
            clipToOutline = true
        }
        val bar = android.widget.FrameLayout(this)
        bar.addView(text("9:41", 13f, 0xFFFFFFFF.toInt(), weight = 600), android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(18f) })
        bar.addView(text("87%", 13f, 0xFFFFFFFF.toInt(), weight = 600), android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(18f) })
        val mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(0xFF000000.toInt(), dp(14f).toFloat())
            setPadding(dp(5f), 0, dp(10f), 0)
        }
        mini.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFF2D55.toInt(), 0xFF5E5CE6.toInt())).apply { cornerRadius = dp(5f).toFloat() }
        }, LinearLayout.LayoutParams(dp(18f), dp(18f)))
        mini.addView(View(this), LinearLayout.LayoutParams(dp(22f), 1))
        mini.addView(View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF26262B.toInt()) } },
            LinearLayout.LayoutParams(dp(11f), dp(11f)))
        mini.addView(View(this), LinearLayout.LayoutParams(dp(22f), 1))
        mini.addView(EqView(this, 0xFFFF5C8A.toInt(), true), LinearLayout.LayoutParams(dp(16f), dp(12f)))
        bar.addView(mini, android.widget.FrameLayout.LayoutParams(-2, dp(28f), Gravity.CENTER))
        scene.addView(bar, android.widget.FrameLayout.LayoutParams(-1, dp(40f), Gravity.TOP).apply { topMargin = dp(8f) })
        scene.addView(text(if (running) "버튼을 누르면 휴대폰 위쪽에서 바로 보입니다" else "권한을 켜면 미리보기를 볼 수 있습니다", 12.5f, 0xE6FFFFFF.toInt()).apply { gravity = Gravity.CENTER },
            android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(12f) })
        parent.addView(scene, LinearLayout.LayoutParams(-1, dp(104f)).apply { topMargin = dp(12f) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12f), 0, dp(10f)) }
        fun chip(label: String, first: Boolean, run: () -> Unit) =
            row.addView(pill(label, run), LinearLayout.LayoutParams(0, dp(44f), 1f).apply { if (!first) marginStart = dp(8f) })
        chip("알림", true) { Demo.notification(this) }
        chip("충전", false) { Demo.charging(this) }
        chip("타이머", false) { Demo.timer(this) }
        parent.addView(row)
    }

    private fun updateDiag() {
        val s = IslandService.instance
        diag?.text = if (s == null) "아일랜드 서비스: 꺼짐 (접근성을 껐다 다시 켜 보세요)" else buildString {
            val g = s.islandGeo; val r = s.islandRect
            appendLine("서비스: 실행 중 · 창 붙음: ${if (s.windowAttached) "예" else "아니오"}")
            appendLine("상태바 감지: ${if (s.statusVisible) "보임" else "숨김(전체화면으로 판단)"}${if (s.probeWorks) "" else " · 감지 대기 중"}")
            appendLine("표시 상태: ${s.islandMode} · 숨김 처리: ${if (s.islandSuppressed) "예" else "아니오"}")
            if (g != null) appendLine("카메라: x=${g.holeCx} y=${g.holeCy} 지름=${g.holeW} · 상태바 ${g.statusH} · 화면 폭 ${g.screenW}")
            if (r != null) appendLine("알약: x=${r[0]} y=${r[1]} 폭=${r[2]} 높이=${r[3]}")
            appendLine("알림 접근 연결: ${if (NotifListener.instance != null) "예" else "아니오"} · 자동 재시작: ${if (Restarter.canWriteSecure(this@MainActivity)) "켜짐" else "꺼짐"}")
            append("오류: ${s.lastError ?: "없음"}")
            val log = EventLog.recent()
            if (log.isNotEmpty()) { append("\n\n최근 기록\n"); append(log.joinToString("\n")) }
        }
    }

    // ---------------------------------------------------------------- Shizuku

    private fun askShizuku() {
        when {
            !Restarter.shizukuRunning() -> toast("Shizuku 앱을 열어 실행한 뒤 다시 눌러 주세요.")
            !Restarter.shizukuAllowed() -> try { Shizuku.requestPermission(7) } catch (_: Throwable) { toast("Shizuku 권한 요청에 실패했습니다.") }
            else -> runGrant()
        }
    }

    private fun runGrant() {
        Thread {
            val ok = Restarter.grantWithShizuku(this)
            runOnUiThread {
                toast(if (ok) "허용했습니다. 이제 업데이트 후에도 저절로 켜집니다." else "허용하지 못했습니다. Shizuku가 실행 중인지 확인해 주세요.")
                build()
            }
        }.start()
    }

    // ---------------------------------------------------------------- building blocks

    private fun text(s: CharSequence, size: Float, color: Int, weight: Int = 400) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = when { weight >= 700 -> Fonts.bold(context); weight >= 600 -> Fonts.semibold(context); else -> Fonts.regular(context) }
    }

    /** Tonal rounded button, One UI style. */
    private fun pill(label: String, onClick: () -> Unit) = text(label, 15f, accent, weight = 600).apply {
        gravity = Gravity.CENTER
        background = Ui.round(tonal, dp(22f).toFloat())
        setPadding(dp(16f), 0, dp(16f), 0)
        isClickable = true
        setOnClickListener { v ->
            v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
            onClick()
        }
    }

    private fun section(title: String) {
        root.addView(text(title, 14f, subColor, weight = 600).apply { setPadding(dp(22f), dp(26f), 0, dp(10f)) })
    }

    private fun note(s: String, button: String? = null, onClick: (() -> Unit)? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22f), dp(10f), dp(22f), 0) }
        box.addView(text(s, 13f, subColor).apply { setLineSpacing(0f, 1.2f) })
        if (button != null && onClick != null) box.addView(pill(button, onClick), LinearLayout.LayoutParams(-2, dp(40f)).apply { topMargin = dp(10f) })
        root.addView(box)
    }

    private fun card(fill: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(cardColor, dp(26f).toFloat())
            setPadding(dp(22f), dp(6f), dp(22f), dp(6f))
        }
        fill(c)
        root.addView(c, LinearLayout.LayoutParams(-1, -2))
    }

    private fun line(parent: LinearLayout, inset: Boolean = true) {
        parent.addView(View(this).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { if (inset) marginStart = dp(54f) })
    }

    /** One UI style coloured round icon in front of a row. */
    private fun badge(res: Int, color: Int): View = android.widget.ImageView(this).apply {
        setImageDrawable(Ui.glyph(context, res, 0xFFFFFFFF.toInt()))
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color) }
        val p = dp(8f); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(38f), dp(38f)).apply { marginEnd = dp(16f) }
    }

    private fun twoLine(title: String, sub: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 17f, textColor))
        if (sub != null) addView(text(sub, 13.5f, subColor).apply { setPadding(0, dp(3f), 0, 0); setLineSpacing(0f, 1.15f) })
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64f); setPadding(0, dp(12f), 0, dp(12f))
    }

    private fun permRow(parent: LinearLayout, icon: Int, color: Int, title: String, sub: String, ok: Boolean, open: () -> Unit) {
        if (parent.childCount > 0) line(parent)
        val r = row()
        r.addView(badge(icon, color))
        r.addView(twoLine(title, sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12f) })
        if (ok) r.addView(text("켜짐", 14f, 0xFF2EB85C.toInt(), weight = 600))
        else r.addView(pill("켜기", open), LinearLayout.LayoutParams(-2, dp(38f)))
        parent.addView(r)
    }

    @Suppress("DEPRECATION")
    private fun toggle(parent: LinearLayout, icon: Int, color: Int, key: String, title: String, sub: String?, last: Boolean = false) {
        val r = row()
        r.addView(badge(icon, color))
        r.addView(twoLine(title, sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12f) })
        val sw = Switch(this).apply {
            isChecked = Prefs.on(this@MainActivity, key)
            setOnCheckedChangeListener { _, on -> Prefs.set(this@MainActivity, key, on) }
        }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        parent.addView(r)
        if (!last) line(parent)
    }

    private fun slider(parent: LinearLayout, title: String, key: String, def: Int, min: Int, max: Int, step: Int, last: Boolean = false, fmt: (Int) -> String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14f), 0, dp(10f)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(title, 17f, textColor), LinearLayout.LayoutParams(0, -2, 1f))
        val value = text(fmt(Prefs.int(this, key, def)), 15f, accent, weight = 600)
        top.addView(value)
        top.addView(text("초기화", 13f, subColor).apply {
            setPadding(dp(14f), dp(6f), 0, dp(6f))
        })
        box.addView(top)
        val sb = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (Prefs.int(this@MainActivity, key, def) - min) / step
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    value.text = fmt(v)
                    if (fromUser) Prefs.setInt(this@MainActivity, key, v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        (top.getChildAt(2) as TextView).setOnClickListener { sb.progress = (def - min) / step; Prefs.setInt(this, key, def) }
        box.addView(sb, LinearLayout.LayoutParams(-1, dp(40f)).apply { topMargin = dp(4f) })
        parent.addView(box)
        if (!last) line(parent, inset = false)
    }
}
