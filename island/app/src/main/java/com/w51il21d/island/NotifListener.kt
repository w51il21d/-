package com.w51il21d.island

import android.app.KeyguardManager
import android.app.Notification
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Reads notifications: music sessions, ongoing calls/timers/navigation, and new messages to preview. */
class NotifListener : NotificationListenerService() {
    companion object {
        var instance: NotifListener? = null
            private set
    }

    private var media: MediaWatcher? = null
    private val seen = LinkedHashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
        EventLog.add("알림 접근 연결됨")
        media = MediaWatcher(this).also { it.start() }
        rescan()
    }

    override fun onListenerDisconnected() {
        EventLog.add("알림 접근 끊김")
        media?.stop(); media = null
        Hub.removeLiveKinds(setOf(Kind.CALL, Kind.NAV, Kind.TIMER))
        instance = null
    }

    override fun onDestroy() {
        if (instance === this) onListenerDisconnected()
        super.onDestroy()
    }

    fun refreshMedia() { media?.refresh() }

    /** Re-reads ongoing notifications, e.g. after a setting changed. */
    fun rescan() {
        Hub.removeLiveKinds(setOf(Kind.CALL, Kind.NAV, Kind.TIMER))
        try { activeNotifications?.forEach { handle(it, null, initial = true) } } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        handle(sbn, rankingMap, initial = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Hub.removeLive(sbn.key)
        Hub.removeAlert("n:" + sbn.key)
        seen.remove(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification, ranking: RankingMap?, initial: Boolean) {
        val n = sbn.notification ?: return
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val ex = n.extras
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return // music: handled by MediaWatcher
        var title = ex.getCharSequence(Notification.EXTRA_TITLE) ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG)
        var text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT)
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val template = ex.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val progressMax = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val signals = Classify.Signals(
            pkg = pkg, category = n.category, ongoing = sbn.isOngoing, chronometer = chrono,
            callStyle = template.endsWith("CallStyle"),
            progress = progressMax > 0 && !ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
            // Android 16 Live Updates ask for promotion with this extra; Samsung's live notifications
            // (Now Bar) use "ongoingActivity" extras. The Samsung key names are a best guess.
            promoted = ex.getBoolean("android.requestPromotedOngoing") || ex.keySet().any { it.contains("ongoingActivity", ignoreCase = true) },
        )

        val kind = Classify.ongoingKind(signals)
        if (kind != null) {
            val prefKey = if (kind == Kind.LIVE) Prefs.LIVE else Prefs.ONGOING
            if (Prefs.on(this, prefKey) && !title.isNullOrBlank()) Hub.putLive(liveItem(sbn, kind, title, text, chrono))
            else Hub.removeLive(sbn.key)
            return
        }

        // Chat apps: read the standard conversation format for the right sender, group name and photo.
        var sender: android.app.Person? = null
        if (template.endsWith("MessagingStyle")) try {
            @Suppress("DEPRECATION")
            val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            val last = Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr).lastOrNull()
            if (last != null) {
                sender = last.senderPerson
                val group = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
                val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                val who = sender?.name
                if (group && conv != null) { title = conv; text = if (who != null) "$who: ${last.text}" else last.text }
                else { if (who != null) title = who; text = last.text }
            }
        } catch (_: Exception) { }
        // Was ongoing (timer, call) and now finished: drop the live item.
        Hub.removeLive(sbn.key)
        if (initial || !Prefs.on(this, Prefs.NOTIF)) return

        val r = Ranking()
        val ranked = ranking?.getRanking(sbn.key, r) == true
        val importance = if (ranked) r.importance else 3
        val passesDnd = if (ranked) r.matchesInterruptionFilter() else true
        val sig = "$title|$text"
        val repeat = seen[sbn.key] == sig || ((n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0 && seen.containsKey(sbn.key))
        seen.remove(sbn.key); seen[sbn.key] = sig
        while (seen.size > 300) seen.remove(seen.keys.first())

        val pm = getSystemService(PowerManager::class.java)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val name = Ui.appLabel(this, pkg) ?: pkg
        if (!pm.isInteractive) { EventLog.add("$name: 화면 꺼짐이라 건너뜀"); return }
        val inFront = !locked && pkg == IslandService.foregroundPkg && !Prefs.on(this, Prefs.SHOW_FRONT)
        // Quiet (low/minimum) notifications: optionally shown too, but never ones the app marked "silent in shade only" when hidden.
        val effectiveImportance = if (Prefs.on(this, Prefs.SHOW_QUIET) && importance >= 1) 3 else importance
        val groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val excluded = pkg in Prefs.excluded(this)
        if (!Classify.shouldAlert(
                ongoing = sbn.isOngoing,
                groupSummary = groupSummary,
                importance = effectiveImportance,
                passesDnd = passesDnd,
                repeat = repeat,
                hasTitle = !title.isNullOrBlank(),
                excluded = excluded,
                appInFront = inFront,
            )) {
            val why = when {
                sbn.isOngoing -> "진행 중 알림"
                groupSummary -> "묶음 요약"
                effectiveImportance < 3 -> "조용한 알림(중요도 $importance)"
                !passesDnd -> "방해 금지"
                repeat -> "같은 내용 반복"
                title.isNullOrBlank() -> "제목 없음"
                excluded -> "제외한 앱"
                inFront -> "지금 보고 있는 앱"
                else -> "기타"
            }
            if (!groupSummary && !sbn.isOngoing) EventLog.add("$name: 건너뜀 - $why")
            return
        }
        EventLog.add("$name: 띄움")
        Prefs.rememberApp(this, pkg)

        val appLabel = Ui.appLabel(this, pkg)
        val hide = locked && Prefs.on(this, Prefs.LOCK_PRIVATE)
        val large = if (hide) null else (sender?.icon ?: n.getLargeIcon())?.let { try { it.loadDrawable(this) } catch (_: Exception) { null } }
        val icon = large ?: Ui.appIcon(this, pkg)
        val open = {
            if (!Ui.fire(this, n.contentIntent)) Ui.launch(this, pkg)
            if ((n.flags and Notification.FLAG_AUTO_CANCEL) != 0) try { cancelNotification(sbn.key) } catch (_: Exception) { }
        }
        Hub.alert(Item(
            key = "n:" + sbn.key, kind = Kind.ALERT,
            title = if (hide) (appLabel ?: "알림") else title!!,
            text = if (hide) "새 알림" else text,
            pkg = pkg, label = appLabel,
            icon = icon, iconPicture = icon != null, iconRound = large != null,
            actions = if (hide) emptyList() else actions(n),
            open = open, shape = Shape.BANNER,
            durationMs = Prefs.notifSeconds(this) * 1000L,
        ))
    }

    private fun actions(n: Notification): List<Act> =
        (n.actions ?: emptyArray()).filter { it.remoteInputs.isNullOrEmpty() && it.title != null && it.actionIntent != null }
            .take(3).map { a -> Act(a.title, Classify.actionStyle(a.title)) { Ui.fire(this, a.actionIntent) } }

    /** Deliveries, rides, downloads: app icon, percent or minutes left, a progress bar when expanded. */
    private fun liveProgressItem(sbn: StatusBarNotification, title: CharSequence, text: CharSequence?): Item {
        val n = sbn.notification
        val pkg = sbn.packageName
        val ex = n.extras
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val prog = if (max > 0) ex.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max else null
        val large = n.getLargeIcon()?.let { try { it.loadDrawable(this) } catch (_: Exception) { null } }
        val icon = large ?: Ui.appIcon(this, pkg)
        val accent = Ui.accentOf(Ui.appIcon(this, pkg)).let { if (it == Ui.WHITE) Ui.BLUE else it }
        val right = Classify.shortEta(title, text) ?: prog?.let { "${(it * 100).toInt()}%" }
        return Item(
            key = sbn.key, kind = Kind.LIVE, title = title, text = text, pkg = pkg,
            label = Ui.appLabel(this, pkg),
            icon = icon, iconPicture = icon != null, iconRound = false,
            accent = accent, rightText = right, rightColor = accent,
            progress = prog?.coerceIn(0f, 1f),
            actions = actions(n),
            open = { if (!Ui.fire(this, n.contentIntent)) Ui.launch(this, pkg) },
        )
    }

    private fun liveItem(sbn: StatusBarNotification, kind: Kind, title: CharSequence, text: CharSequence?, chrono: Boolean): Item {
        val n = sbn.notification
        val pkg = sbn.packageName
        val ex = n.extras
        if (kind == Kind.LIVE) return liveProgressItem(sbn, title, text)
        val (glyph, accent) = when (kind) {
            Kind.CALL -> R.drawable.ic_phone to Ui.GREEN
            Kind.NAV -> R.drawable.ic_navigation to Ui.BLUE
            else -> R.drawable.ic_timer to Ui.ORANGE
        }
        val large = n.getLargeIcon()?.let { try { it.loadDrawable(this) } catch (_: Exception) { null } }
        // Navigation apps put the turn arrow in the large icon; calls put the caller photo there.
        val usePicture = large != null && (kind == Kind.NAV || kind == Kind.CALL)
        val right: CharSequence? = when {
            chrono -> null
            kind == Kind.NAV -> Classify.shortDistance(title, text) ?: title.toString().take(6)
            else -> Classify.shortTime(title, text)
        }
        return Item(
            key = sbn.key, kind = kind, title = title, text = text, pkg = pkg,
            label = Ui.appLabel(this, pkg),
            icon = if (usePicture) large else Ui.glyph(this, glyph, accent),
            iconPicture = usePicture, iconRound = usePicture && kind == Kind.CALL,
            accent = accent,
            rightText = right ?: if (chrono) null else when (kind) { Kind.CALL -> "수신 중"; Kind.TIMER -> "진행 중"; else -> null },
            rightColor = accent,
            chronoBase = if (chrono) n.`when` else null,
            countDown = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            actions = actions(n),
            open = { if (!Ui.fire(this, n.contentIntent)) Ui.launch(this, pkg) },
        )
    }
}
