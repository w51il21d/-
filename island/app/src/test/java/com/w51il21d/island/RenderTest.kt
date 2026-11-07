package com.w51il21d.island

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import kotlin.math.abs

/**
 * Renders every island state on a mock S24+ status bar and writes build/qa/*.png,
 * so the look can be checked without a phone. Also checks the pill stays centred on the camera.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w384dp-h832dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val d get() = ctx.resources.displayMetrics.density
    // Approximate S24+ (1080p mode scaled to this density): camera hole about 15dp wide, centred, status bar about 36dp.
    private val geo get() = Geo(
        screenW = (384 * d).toInt(), holeCx = (192 * d).toInt(), holeCy = (18 * d).toInt(),
        holeW = (15 * d).toInt(), holeH = (15 * d).toInt(), statusH = (36 * d).toInt(), density = d,
    )

    @Before fun setUp() { Hub.reset(); IslandView.animate = false }
    @After fun tearDown() { Hub.reset(); IslandView.animate = true }

    private fun art(): BitmapDrawable {
        val b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 120f, 120f, 0xFFFF2D55.toInt(), 0xFF5E5CE6.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(60f, 60f, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF })
        return BitmapDrawable(ctx.resources, b)
    }

    private fun appIcon(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 30f; setSize(120, 120) }

    private fun media(playing: Boolean = true): Item {
        val a = art()
        return Item(
            key = "media", kind = Kind.MEDIA, title = "밤양갱", text = "비비 (BIBI)", label = "YouTube Music",
            icon = a, iconPicture = true, accent = Ui.accentOf(a.bitmap),
            media = MediaInfo(playing, 147_000, { 52_000 }, {}, {}, {}, {}),
        )
    }

    private fun call() = Item(
        key = "call", kind = Kind.CALL, title = "엄마", text = "휴대전화", label = "전화",
        icon = Ui.glyph(ctx, R.drawable.ic_phone, Ui.GREEN), accent = Ui.GREEN,
        chronoBase = System.currentTimeMillis() - 83_000,
        actions = listOf(Act("스피커") {}, Act("끊기", Act.Style.DANGER) {}),
    )

    private fun nav() = Item(
        key = "nav", kind = Kind.NAV, title = "300m 후 우회전", text = "테헤란로 방면 · 도착 14:52", label = "T map",
        icon = Ui.glyph(ctx, R.drawable.ic_navigation, Ui.BLUE), accent = Ui.BLUE,
        rightText = "300m", rightColor = Ui.BLUE, actions = listOf(Act("안내 종료", Act.Style.DANGER) {}),
    )

    private fun message() = Demo.notificationItem(ctx).copy(icon = appIcon(0xFFFEE500.toInt()), iconPicture = true, label = "카카오톡")

    private fun ringer() = Item(key = "ringer", kind = Kind.ALERT, title = "무음 모드", label = "소리 모드",
        icon = Ui.glyph(ctx, R.drawable.ic_bell_off, Ui.RED), accent = Ui.RED, rightText = "무음", rightColor = Ui.RED)

    private fun bt() = Item(key = "bt", kind = Kind.ALERT, title = "Galaxy Buds3 Pro", text = "연결됨", label = "블루투스",
        icon = Ui.glyph(ctx, R.drawable.ic_headset, Ui.BLUE), accent = Ui.BLUE, rightText = "85%", rightColor = Ui.GREEN)

    private fun lowBat() = Item(key = "lowbat", kind = Kind.ALERT, title = "배터리 부족", text = "배터리가 10% 남았습니다. 절전 모드를 켜거나 충전하세요.",
        label = "배터리", icon = Ui.glyph(ctx, R.drawable.ic_battery_alert, Ui.RED), accent = Ui.RED, rightText = "10%", rightColor = Ui.RED)

    private class Shot(val name: String, val bmp: Bitmap, val place: IntArray, val mode: IslandView.Mode)

    private fun render(name: String, item: Item?, expand: Boolean = false, idle: Boolean = false): Shot {
        val v = IslandView(ctx, geo) { _, _, _, _, _ -> }
        v.idlePill = idle
        v.show(item)
        if (expand) v.expand()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        val (x, y, w, h) = v.lastPlace.toList()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val g = geo
        val frameH = maxOf(y + h + (14 * d).toInt(), (64 * d).toInt())
        val bmp = Bitmap.createBitmap(g.screenW, frameH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, g.screenW.toFloat(), frameH.toFloat(), 0xFF7F9CF5.toInt(), 0xFFF6AD8F.toInt(), Shader.TileMode.CLAMP) })
        val sbText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * d; typeface = Typeface.DEFAULT_BOLD }
        c.drawText("9:41", 22 * d, g.holeCy + 5 * d, sbText)
        c.drawText("5G  87%", g.screenW - 90 * d, g.holeCy + 5 * d, sbText)
        c.save(); c.translate(x.toFloat(), y.toFloat()); v.draw(c); c.restore()
        // The real camera lens, drawn on top: it must sit inside the black pill.
        c.drawCircle(g.holeCx.toFloat(), g.holeCy.toFloat(), g.holeW / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202024.toInt() })
        c.drawCircle(g.holeCx.toFloat(), g.holeCy.toFloat(), g.holeW / 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A50.toInt() })
        c.drawText(name, 12 * d, frameH - 5 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt(); textSize = 10 * d })
        return Shot(name, bmp, intArrayOf(x, y, w, h), v.mode)
    }

    private fun save(name: String, shots: List<Shot>) {
        val gap = (6 * d).toInt()
        val sheet = Bitmap.createBitmap(shots.maxOf { it.bmp.width }, shots.sumOf { it.bmp.height + gap }, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(Color.WHITE)
        var top = 0f
        for (s in shots) { c.drawBitmap(s.bmp, 0f, top, null); top += s.bmp.height + gap }
        val dir = File("build/qa"); dir.mkdirs()
        File(dir, "$name.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun allStates() {
        val compact = listOf(
            render("음악 (작게)", media()),
            render("음악 일시정지", media(playing = false)),
            render("통화", call()),
            render("길 안내", nav()),
            render("타이머", Demo.timerItem(ctx)),
            render("충전", Demo.chargingItem(ctx)),
            render("무음 전환", ringer()),
            render("블루투스", bt()),
            render("배터리 부족", lowBat()),
            render("대기 알약", null, idle = true),
        )
        val wide = listOf(
            render("알림 미리보기", message()),
            render("음악 (펼침)", media(), expand = true),
            render("통화 (펼침)", call(), expand = true),
            render("알림 (펼침)", message(), expand = true),
            render("길 안내 (펼침)", nav(), expand = true),
            render("충전 (펼침)", Demo.chargingItem(ctx), expand = true),
        )
        save("island-compact", compact)
        save("island-expanded", wide)

        val g = geo
        for (s in compact + wide) {
            val (x, _, w, _) = s.place.toList()
            assertTrue("${s.name}: pill must be centred on the camera", abs(x + w / 2 - g.holeCx) <= 1)
            assertTrue("${s.name}: pill must be wider than the camera", w > g.holeW)
        }
        for (s in compact.dropLast(1)) {
            assertEquals(s.name, IslandView.Mode.COMPACT, s.mode)
            assertTrue("${s.name}: compact pill too wide (${s.place[2]})", s.place[2] < g.screenW * 0.75)
        }
        assertEquals(IslandView.Mode.BANNER, wide[0].mode)
        for (s in wide.drop(1)) {
            assertEquals(s.name, IslandView.Mode.EXPANDED, s.mode)
            assertEquals(g.screenW - (20 * d).toInt(), s.place[2])
        }
    }

    @Test fun animatedTransitionsDoNotCrash() {
        IslandView.animate = true
        val v = IslandView(ctx, geo) { _, _, _, _, _ -> }
        val looper = shadowOf(Looper.getMainLooper())
        v.show(media()); looper.idleFor(Duration.ofMillis(600))
        v.expand(); looper.idleFor(Duration.ofMillis(100))
        v.show(call()); looper.idleFor(Duration.ofMillis(100))
        v.collapse(); looper.idleFor(Duration.ofMillis(600))
        v.setSuppressed(true); v.setSuppressed(false)
        v.show(message()); looper.idleFor(Duration.ofMillis(8000))
        v.show(null); looper.idleFor(Duration.ofMillis(600))
        assertEquals(IslandView.Mode.HIDDEN, v.mode)
    }

    @Test fun expandedAutoCollapses() {
        val v = IslandView(ctx, geo) { _, _, _, _, _ -> }
        v.show(call()); v.expand()
        assertEquals(IslandView.Mode.EXPANDED, v.mode)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(7100))
        assertEquals(IslandView.Mode.COMPACT, v.mode)
    }

    @Test fun allDrawablesLoad() {
        for (f in R.drawable::class.java.fields) assertNotNull(f.name, ctx.getDrawable(f.getInt(null)))
    }

    @Test fun settingsScreen() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val scroll = (a.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ViewGroup
        val content = scroll.getChildAt(0)
        val w = (384 * d).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, w, content.measuredHeight)
        val bmp = Bitmap.createBitmap(w, content.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFFF2F2F7.toInt()); content.draw(c)
        val dir = File("build/qa"); dir.mkdirs()
        File(dir, "settings.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
