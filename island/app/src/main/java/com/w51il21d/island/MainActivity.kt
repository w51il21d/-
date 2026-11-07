package com.w51il21d.island

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Settings: permissions, features, size and position, preview buttons. Built in code (no XML layouts). */
class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val cardColor get() = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    private val pageColor get() = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    private val textColor get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    private val subColor get() = if (dark) 0xFF98989F.toInt() else 0xFF6C6C70.toInt()

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(20f), dp(16f), dp(32f)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(pageColor); addView(root); fitsSystemWindows = true }
        window.statusBarColor = pageColor
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    // ---------------------------------------------------------------- state

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, IslandService::class.java)
        return s.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun notifOn(): Boolean =
        getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this, NotifListener::class.java))

    private fun btOn() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- UI

    private fun build() {
        root.removeAllViews()
        root.addView(text("아일랜드", 30f, textColor, bold = true))
        val running = IslandService.instance != null
        root.addView(text(if (running) "실행 중입니다. 화면 위 카메라 둘레를 보세요." else "아래 권한을 켜면 시작됩니다.", 15f,
            if (running) Ui.GREEN else subColor).apply { setPadding(0, dp(4f), 0, dp(8f)) })

        section("권한")
        card {
            permRow(it, "접근성: 아일랜드 표시", "화면 위에 아일랜드를 띄웁니다. 꼭 필요합니다.", a11yOn()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            permRow(it, "알림 접근", "음악, 통화, 타이머, 길 안내, 알림 미리보기에 필요합니다.", notifOn()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            permRow(it, "근처 기기", "연결된 블루투스 이어폰 이름을 보여줄 때 씁니다. 없어도 됩니다.", btOn()) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            }
        }
        if (!a11yOn() || !notifOn()) card {
            it.addView(text("켜기 버튼이 회색이라 눌리지 않으면", 15f, textColor, bold = true))
            it.addView(text("직접 설치한 앱이라 Android가 막아 둔 상태입니다.\n① 아래 [앱 정보 열기] → 오른쪽 위 ⋮ → [제한된 설정 허용]\n② 다시 이 화면으로 와서 접근성과 알림 접근을 켜세요.", 14f, subColor)
                .apply { setPadding(0, dp(4f), 0, dp(8f)) })
            it.addView(button("앱 정보 열기") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }

        section("보여줄 내용")
        card {
            toggle(it, Prefs.MEDIA, "음악 재생", "앨범 사진과 재생 막대. 누르면 재생 조작")
            toggle(it, Prefs.ONGOING, "통화 · 타이머 · 길 안내", "진행 중인 시간과 다음 안내를 계속 표시")
            toggle(it, Prefs.NOTIF, "알림 미리보기", "새 알림을 잠깐 펼쳐서 보여줍니다")
            toggle(it, Prefs.CHARGING, "충전", "충전기를 꽂을 때와 완전히 찼을 때")
            toggle(it, Prefs.LOWBAT, "배터리 부족", "20%, 10%, 5%가 될 때 한 번씩")
            toggle(it, Prefs.RINGER, "소리 · 진동 · 무음 전환", "소리 모드를 바꿀 때")
            toggle(it, Prefs.BT, "블루투스 이어폰 연결", "이어폰, 스피커, 차량 연결 시")
        }

        section("동작")
        card {
            toggle(it, Prefs.LOCK_PRIVATE, "잠금 화면에서 알림 내용 숨기기", "앱 이름과 '새 알림'만 표시")
            toggle(it, Prefs.HIDE_LAND, "가로 화면에서 숨기기", "영상·게임을 가로로 볼 때")
            toggle(it, Prefs.IDLE, "아무것도 없을 때 작은 알약 표시", "카메라 둘레에 검은 알약을 늘 띄웁니다")
            stepper(it, "알림 표시 시간", Prefs.NOTIF_SEC, 4, 2, 10, 1) { "${it}초" }
        }

        section("위치와 크기")
        card {
            stepper(it, "크기", Prefs.SCALE, 100, 80, 130, 5) { "$it%" }
            stepper(it, "세로 위치", Prefs.OFFSET, 0, -12, 12, 1) { if (it == 0) "기본" else if (it > 0) "아래로 $it" else "위로 ${-it}" }
        }

        section("미리보기")
        card {
            it.addView(text("아일랜드가 실행 중일 때 눌러 보세요.", 14f, subColor).apply { setPadding(0, 0, 0, dp(8f)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("알림") { Demo.notification(this) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("충전") { Demo.charging(this) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8f) })
            row.addView(button("타이머") { Demo.timer(this) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8f) })
            it.addView(row)
        }

        val apps = Prefs.seenApps(this)
        if (apps.isNotEmpty()) {
            section("알림 미리보기 앱")
            card {
                it.addView(text("체크를 풀면 그 앱의 알림은 아일랜드에 띄우지 않습니다.", 14f, subColor).apply { setPadding(0, 0, 0, dp(4f)) })
                val ex = Prefs.excluded(this)
                for (pkg in apps) {
                    val cb = CheckBox(this).apply {
                        text = Ui.appLabel(this@MainActivity, pkg) ?: pkg
                        setTextColor(textColor); textSize = 16f
                        isChecked = pkg !in ex
                        setOnCheckedChangeListener { _, on -> Prefs.setExcluded(this@MainActivity, pkg, !on) }
                    }
                    it.addView(cb, LinearLayout.LayoutParams(-1, dp(48f)))
                }
            }
        }

        root.addView(text("버전 ${packageManager.getPackageInfo(packageName, 0).versionName}", 12f, subColor)
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(20f), 0, 0) })
    }

    private fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun section(title: String) {
        root.addView(text(title, 14f, subColor, bold = true).apply { setPadding(dp(14f), dp(22f), 0, dp(8f)) })
    }

    private fun card(fill: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(cardColor, dp(22f).toFloat())
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
        }
        fill(c)
        root.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4f) })
    }

    private fun twoLine(title: String, sub: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 16.5f, textColor))
        addView(text(sub, 13.5f, subColor).apply { setPadding(0, dp(2f), 0, 0) })
    }

    private fun permRow(parent: LinearLayout, title: String, sub: String, ok: Boolean, open: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8f), 0, dp(8f)) }
        row.addView(twoLine(title, sub), LinearLayout.LayoutParams(0, -2, 1f))
        if (ok) row.addView(text("켜짐", 15f, Ui.GREEN, bold = true).apply { setPadding(dp(12f), 0, 0, 0) })
        else row.addView(button("켜기", open))
        parent.addView(row)
    }

    @Suppress("DEPRECATION")
    private fun toggle(parent: LinearLayout, key: String, title: String, sub: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8f), 0, dp(8f)) }
        row.addView(twoLine(title, sub), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            isChecked = Prefs.on(this@MainActivity, key)
            setOnCheckedChangeListener { _, on -> Prefs.set(this@MainActivity, key, on) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        parent.addView(row)
    }

    private fun stepper(parent: LinearLayout, title: String, key: String, def: Int, min: Int, max: Int, step: Int, fmt: (Int) -> String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6f), 0, dp(6f)) }
        row.addView(text(title, 16.5f, textColor), LinearLayout.LayoutParams(0, -2, 1f))
        val value = text(fmt(Prefs.int(this, key, def)), 15f, textColor, bold = true).apply { gravity = Gravity.CENTER; minWidth = dp(72f) }
        fun change(d: Int) {
            val v = (Prefs.int(this, key, def) + d).coerceIn(min, max)
            Prefs.setInt(this, key, v); value.text = fmt(v)
        }
        row.addView(button("−") { change(-step) }, LinearLayout.LayoutParams(dp(52f), -2))
        row.addView(value)
        row.addView(button("+") { change(step) }, LinearLayout.LayoutParams(dp(52f), -2))
        val reset = text("초기화", 13f, Ui.BLUE).apply {
            setPadding(dp(10f), dp(8f), 0, dp(8f))
            setOnClickListener { Prefs.setInt(this@MainActivity, key, def); value.text = fmt(def) }
            visibility = View.VISIBLE
        }
        row.addView(reset)
        parent.addView(row)
    }
}
