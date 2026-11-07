package com.w51il21d.island

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.SystemClock

/** Charging, battery, sound mode and Bluetooth events → short island alerts. */
class SystemEvents(private val ctx: Context) {
    private companion object {
        const val BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val BT_BATTERY_EXTRA = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }

    private var plugged = false
    private var warned = 101
    private var fullShown = false
    private var btAddr: String? = null
    private var btName: CharSequence = ""
    private var btAt = 0L
    private val btLast = HashMap<String, Long>()

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBattery(i, isInitialStickyBroadcast)
                Intent.ACTION_POWER_CONNECTED -> onPlug()
                Intent.ACTION_POWER_DISCONNECTED -> { plugged = false; fullShown = false }
                AudioManager.RINGER_MODE_CHANGED_ACTION ->
                    if (!isInitialStickyBroadcast) onRinger(i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1))
                BluetoothDevice.ACTION_ACL_CONNECTED -> onBt(i)
                BT_BATTERY -> onBtBattery(i)
            }
        }
    }

    fun register() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BT_BATTERY)
        }
        ctx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
    }

    fun unregister() { try { ctx.unregisterReceiver(rx) } catch (_: Exception) { } }

    private fun level(): Int = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun onBattery(i: Intent, initial: Boolean) {
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
        if (lvl < 0) return
        val isPlugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val full = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_FULL
        if (initial) {
            plugged = isPlugged
            warned = Classify.LOW_LEVELS.filter { lvl <= it }.minOrNull() ?: 101
            fullShown = full
            return
        }
        if (isPlugged) {
            warned = 101
            if (full && !fullShown && Prefs.on(ctx, Prefs.CHARGING)) {
                fullShown = true
                Hub.alert(Item(key = "charge", kind = Kind.ALERT, title = "충전 완료", label = "배터리",
                    icon = Ui.glyph(ctx, R.drawable.ic_bolt, Ui.GREEN), accent = Ui.GREEN,
                    rightText = "100%", rightColor = Ui.GREEN, durationMs = 3500))
            }
            return
        }
        val t = Classify.lowBatteryCrossed(lvl, warned) ?: return
        warned = t
        if (!Prefs.on(ctx, Prefs.LOWBAT)) return
        Hub.alert(Item(key = "lowbat", kind = Kind.ALERT, title = "배터리 부족", label = "배터리",
            text = "배터리가 $lvl% 남았습니다. 절전 모드를 켜거나 충전하세요.",
            icon = Ui.glyph(ctx, R.drawable.ic_battery_alert, Ui.RED), accent = Ui.RED,
            rightText = "$lvl%", rightColor = Ui.RED, durationMs = 4000))
    }

    fun onPlug() {
        plugged = true
        if (!Prefs.on(ctx, Prefs.CHARGING)) return
        val lvl = level()
        val remain = ctx.getSystemService(BatteryManager::class.java).computeChargeTimeRemaining()
        val text = if (remain > 0) "완전 충전까지 약 ${minutes(remain)}" else "충전을 시작했습니다"
        Hub.alert(Item(key = "charge", kind = Kind.ALERT, title = "충전 중", text = text, label = "배터리",
            icon = Ui.glyph(ctx, R.drawable.ic_bolt, Ui.GREEN), accent = Ui.GREEN,
            rightText = "$lvl%", rightColor = Ui.GREEN, durationMs = 3000))
    }

    private fun minutes(ms: Long): String {
        val m = (ms / 60000).toInt().coerceAtLeast(1)
        return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
    }

    fun onRinger(mode: Int) {
        if (!Prefs.on(ctx, Prefs.RINGER)) return
        val (res, label, color) = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> Triple(R.drawable.ic_bell_off, "무음", Ui.RED)
            AudioManager.RINGER_MODE_VIBRATE -> Triple(R.drawable.ic_vibrate, "진동", Ui.ORANGE)
            AudioManager.RINGER_MODE_NORMAL -> Triple(R.drawable.ic_bell, "소리", Ui.WHITE)
            else -> return
        }
        Hub.alert(Item(key = "ringer", kind = Kind.ALERT, title = "$label 모드", label = "소리 모드",
            icon = Ui.glyph(ctx, res, color), accent = color, rightText = label, rightColor = color, durationMs = 2000))
    }

    @Suppress("DEPRECATION")
    private fun device(i: Intent): BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun onBt(i: Intent) {
        if (!Prefs.on(ctx, Prefs.BT)) return
        val d = device(i) ?: return
        var name: CharSequence = "블루투스 기기"
        try {
            val major = d.bluetoothClass?.majorDeviceClass
            // Watches reconnect often; only announce headphones, speakers and car audio.
            if (major != null && major != BluetoothClass.Device.Major.AUDIO_VIDEO) return
            (d.alias ?: d.name)?.let { name = it }
        } catch (_: SecurityException) { }
        val now = SystemClock.elapsedRealtime()
        if (now - (btLast[d.address] ?: -600_000L) < 60_000) return
        btLast[d.address] = now
        btAddr = d.address; btName = name; btAt = now
        showBt(null)
    }

    private fun onBtBattery(i: Intent) {
        val d = device(i) ?: return
        val lvl = i.getIntExtra(BT_BATTERY_EXTRA, -1)
        if (lvl < 0 || d.address != btAddr || SystemClock.elapsedRealtime() - btAt > 15_000) return
        if (!Prefs.on(ctx, Prefs.BT)) return
        showBt(lvl)
    }

    private fun showBt(level: Int?) {
        Hub.alert(Item(key = "bt", kind = Kind.ALERT, title = btName, text = "연결됨", label = "블루투스",
            icon = Ui.glyph(ctx, R.drawable.ic_headset, Ui.BLUE), accent = Ui.BLUE,
            rightText = level?.let { "$it%" } ?: "연결됨", rightColor = if (level != null) Ui.GREEN else Ui.BLUE,
            durationMs = 3000))
    }
}
