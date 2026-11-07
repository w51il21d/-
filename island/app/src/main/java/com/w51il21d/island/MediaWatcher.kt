package com.w51il21d.island

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Follows the phone's music players through their media sessions (needs notification access). */
class MediaWatcher(private val ctx: Context) {
    companion object { const val KEY = "media" }

    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val comp = ComponentName(ctx, NotifListener::class.java)
    private val h = Handler(Looper.getMainLooper())
    private val tracked = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var current: MediaController? = null
    private var lastArt: Bitmap? = null
    private var lastSig = -1L
    private var lastIcon: android.graphics.drawable.Drawable? = null
    private var lastBackdrop: Bitmap? = null
    private var lastAccent = Ui.WHITE
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { onSessions(it ?: emptyList()) }
    private val dropPaused = Runnable { current = null; Hub.removeLive(KEY) }

    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessions, comp, h)
            onSessions(msm.getActiveSessions(comp))
        } catch (_: SecurityException) { }
    }

    fun stop() {
        try { msm.removeOnActiveSessionsChangedListener(sessions) } catch (_: Exception) { }
        for ((c, cb) in tracked.values) c.unregisterCallback(cb)
        tracked.clear()
        h.removeCallbacks(dropPaused)
        current = null
        Hub.removeLive(KEY)
    }

    private fun onSessions(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        tracked.keys.filter { it !in tokens }.forEach { t -> tracked.remove(t)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        for (c in list) if (c.sessionToken !in tracked) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() = refresh()
            }
            c.registerCallback(cb, h)
            tracked[c.sessionToken] = c to cb
        }
        refresh()
    }

    private fun isPlaying(c: MediaController): Boolean {
        val s = c.playbackState?.state
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
    }

    fun refresh() {
        if (!Prefs.on(ctx, Prefs.MEDIA)) { h.removeCallbacks(dropPaused); current = null; Hub.removeLive(KEY); return }
        val all = tracked.values.map { it.first }
        val playing = all.firstOrNull { isPlaying(it) }
        val c = playing ?: current?.let { cur -> all.firstOrNull { it.sessionToken == cur.sessionToken } }
        if (c?.metadata == null) { h.removeCallbacks(dropPaused); current = null; Hub.removeLive(KEY); return }
        val wasPlaying = current?.let { isPlaying(it) } ?: false
        current = c
        val on = isPlaying(c)
        if (on) h.removeCallbacks(dropPaused)
        else if (wasPlaying || !h.hasCallbacks(dropPaused)) { h.removeCallbacks(dropPaused); h.postDelayed(dropPaused, 60_000) }
        publish(c, on)
    }

    /** Name of connected earbuds/headphones, or null when sound comes from the phone. */
    private fun outputName(): String? = try {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val types = setOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in types }
            ?.let { d -> d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL } ?: "이어폰" }
    } catch (_: Exception) { null }

    private fun publish(c: MediaController, playing: Boolean) {
        val md = c.metadata ?: return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        // Players re-send the same picture as a new object on every update; reuse ours when the content
        // is the same, otherwise the island thinks the art changed and replays its change animation (flicker).
        if (art !== lastArt) {
            lastArt = art
            val sig = Ui.signature(art)
            if (sig != lastSig) {
                lastSig = sig
                lastAccent = Ui.accentOf(art)
                lastIcon = art?.let { BitmapDrawable(ctx.resources, it) }
                lastBackdrop = Ui.backdrop(art)
            }
        }
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val tc = c.transportControls
        val pkg = c.packageName
        val info = MediaInfo(
            playing = playing,
            durationMs = dur,
            positionMs = {
                val s = c.playbackState
                if (s == null) 0L else {
                    var p = s.position
                    if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0)
                        p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
                    if (dur > 0) p.coerceIn(0, dur) else maxOf(0L, p)
                }
            },
            toggle = { if (isPlaying(c)) tc.pause() else tc.play() },
            next = { tc.skipToNext() },
            prev = { tc.skipToPrevious() },
            seek = { tc.seekTo(it) },
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() && it != title },
            output = outputName(),
            backdrop = lastBackdrop,
        )
        val icon = lastIcon ?: Ui.appIcon(ctx, pkg)
        Hub.putLive(Item(
            key = KEY, kind = Kind.MEDIA, title = title, text = artist, pkg = pkg,
            label = Ui.appLabel(ctx, pkg), icon = icon, iconPicture = icon != null,
            accent = lastAccent, media = info,
            open = { if (!Ui.fire(ctx, c.sessionActivity)) Ui.launch(ctx, pkg) },
            rank = if (playing) Kind.MEDIA.priority else 20,
        ))
    }
}
