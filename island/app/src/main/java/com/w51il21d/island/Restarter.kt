package com.w51il21d.island

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import rikka.shizuku.Shizuku

/**
 * After an app update Android kills the services and does not always bring the accessibility
 * service back. This wakes the app on update/boot, re-attaches notification access and, if allowed
 * once through Shizuku, switches the accessibility service off and on again.
 */
object Restarter {
    private const val WAS_RUNNING = "service_was_running"

    fun markRunning(c: Context, on: Boolean) = Prefs.set(c, WAS_RUNNING, on)

    fun canWriteSecure(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Blocking; call off the main thread. */
    fun heal(c: Context) {
        try { NotificationListenerService.requestRebind(ComponentName(c, NotifListener::class.java)) } catch (_: Exception) { }
        if (IslandService.instance != null) return
        if (!Prefs.sp(c).getBoolean(WAS_RUNNING, false) || !canWriteSecure(c)) return
        val cr = c.contentResolver
        val me = ComponentName(c, IslandService::class.java)
        val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val others = cur.split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        try {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            Thread.sleep(400)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me.flattenToString()).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (_: Exception) { }
    }

    fun healAsync(c: Context, delayMs: Long, done: () -> Unit = {}) {
        val app = c.applicationContext
        Thread { try { Thread.sleep(delayMs); heal(app) } finally { done() } }.start()
    }

    // ---- Shizuku: used once to give this app WRITE_SECURE_SETTINGS.

    fun shizukuRunning() = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    fun shizukuAllowed() = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }

    /** Runs `pm grant` as the shell user through Shizuku. Blocking. */
    fun grantWithShizuku(c: Context): Boolean = try {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("pm", "grant", c.packageName, Manifest.permission.WRITE_SECURE_SETTINGS), null, null) as Process
        p.waitFor() == 0 && canWriteSecure(c)
    } catch (_: Throwable) { false }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        Restarter.healAsync(c, 2500) { pending.finish() }
    }
}
