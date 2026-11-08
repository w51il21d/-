package com.w51il21d.island

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val MEDIA = "media"
    const val CHARGING = "charging"
    const val NOTIF = "notifications"
    const val ONGOING = "ongoing"
    const val RINGER = "ringer"
    const val BT = "bluetooth"
    const val LOWBAT = "low_battery"
    const val IDLE = "idle_pill"
    const val HIDE_LAND = "hide_landscape"
    const val HIDE_FULL = "hide_fullscreen"
    const val GLOW = "glow"
    const val SHOW_QUIET = "show_quiet"
    const val LIVE = "live_activities"
    const val STATUS = "status_icons"
    const val BATT_NUM = "battery_number"
    const val SHOW_FRONT = "show_front"
    const val LOCK_PRIVATE = "lock_private"
    const val OFFSET = "offset_dp"
    const val SCALE = "scale_pct"
    const val NOTIF_SEC = "notif_seconds"
    private const val EXCLUDED = "excluded"
    private const val SEEN = "seen_apps"

    private val defaults = mapOf(IDLE to false, SHOW_QUIET to false, SHOW_FRONT to false)

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences("island", Context.MODE_PRIVATE)
    fun on(c: Context, key: String) = sp(c).getBoolean(key, defaults[key] ?: true)
    fun set(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()
    fun int(c: Context, key: String, def: Int) = sp(c).getInt(key, def)
    fun setInt(c: Context, key: String, v: Int) = sp(c).edit().putInt(key, v).apply()

    fun offset(c: Context) = int(c, OFFSET, 0)
    fun scale(c: Context) = int(c, SCALE, 100)
    fun notifSeconds(c: Context) = int(c, NOTIF_SEC, 4)

    fun excluded(c: Context): Set<String> = sp(c).getStringSet(EXCLUDED, emptySet())!!
    fun setExcluded(c: Context, pkg: String, ex: Boolean) {
        val s = excluded(c).toMutableSet(); if (ex) s += pkg else s -= pkg
        sp(c).edit().putStringSet(EXCLUDED, s).apply()
    }

    /** Apps that recently sent a notification, newest first, for the exclude list. */
    fun seenApps(c: Context): List<String> =
        sp(c).getString(SEEN, "")!!.split('|').filter { it.isNotBlank() }

    fun rememberApp(c: Context, pkg: String) {
        val l = seenApps(c).toMutableList()
        if (l.firstOrNull() == pkg) return
        l.remove(pkg); l.add(0, pkg)
        sp(c).edit().putString(SEEN, l.take(40).joinToString("|")).apply()
    }
}
