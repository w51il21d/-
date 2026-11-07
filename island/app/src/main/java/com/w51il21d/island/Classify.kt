package com.w51il21d.island

/** Pure rules for turning a notification into an island item. Kept free of Android calls so they are unit-tested. */
object Classify {
    val NAV_PKGS = setOf(
        "com.google.android.apps.maps", "com.skt.tmap.ku", "com.skt.skaf.l001mtm091",
        "com.locnall.KimGiSa", "com.nhn.android.nmap", "net.daum.android.map", "com.waze",
        "kr.mappers.AtlanSmart", "com.thinkware.inavi3d", "com.here.app.maps", "com.sygic.aura",
    )
    /** System packages whose ongoing progress notifications are noise, not activities. */
    val NOISE_PKGS = setOf("android", "com.android.systemui", "com.samsung.android.lool", "com.samsung.android.da.daagent")
    val CALL_PKGS = setOf(
        "com.samsung.android.incallui", "com.android.incallui", "com.google.android.dialer", "com.skt.prod.dialer",
    )
    val CLOCK_PKGS = setOf("com.sec.android.app.clockpackage", "com.google.android.deskclock")

    /** What we can read from a notification, without Android types. */
    data class Signals(
        val pkg: String,
        val category: String?,
        val ongoing: Boolean,
        val chronometer: Boolean,
        /** Uses the standard call template (phone, KakaoTalk voice, Discord, WhatsApp...). */
        val callStyle: Boolean = false,
        /** Has a determinate progress bar. */
        val progress: Boolean = false,
        /** Android 16 "Live Update" or a vendor live-activity notification. */
        val promoted: Boolean = false,
    )

    fun ongoingKind(pkg: String, category: String?, ongoing: Boolean, chronometer: Boolean): Kind? =
        ongoingKind(Signals(pkg, category, ongoing, chronometer))

    fun ongoingKind(s: Signals): Kind? {
        if (!s.ongoing && !s.promoted) return null
        return when {
            s.callStyle || s.category == "call" || s.pkg in CALL_PKGS -> Kind.CALL
            s.category == "navigation" || s.pkg in NAV_PKGS -> Kind.NAV
            s.category == "stopwatch" || s.category == "alarm" || s.pkg in CLOCK_PKGS -> Kind.TIMER
            s.chronometer -> Kind.TIMER
            s.pkg in NOISE_PKGS -> null
            s.promoted || s.progress -> Kind.LIVE
            else -> null
        }
    }

    private val ETA = Regex("""\d+\s?(?:분|min|mins)(?![a-zA-Z])|\d{1,2}:\d{2}\s?도착""")

    /** "약 12분 후 도착" → "12분". */
    fun shortEta(vararg s: CharSequence?): String? =
        s.firstNotNullOfOrNull { it?.let { t -> ETA.find(t)?.value?.replace(" ", "") } }

    fun shouldAlert(
        ongoing: Boolean,
        groupSummary: Boolean,
        importance: Int,
        passesDnd: Boolean,
        repeat: Boolean,
        hasTitle: Boolean,
        excluded: Boolean,
        appInFront: Boolean,
    ): Boolean = !ongoing && !groupSummary && importance >= 3 /* IMPORTANCE_DEFAULT */ &&
        passesDnd && !repeat && hasTitle && !excluded && !appInFront

    private val TIME = Regex("""(?<![\d:])\d{1,2}:\d{2}(?::\d{2})?(?![\d:])""")
    private val DIST = Regex("""\d+(?:[.,]\d+)?\s?(?:km|m|킬로미터|미터)(?![a-zA-Z])""")

    fun shortTime(vararg s: CharSequence?): String? =
        s.firstNotNullOfOrNull { it?.let { t -> TIME.find(t)?.value } }

    fun shortDistance(vararg s: CharSequence?): String? =
        s.firstNotNullOfOrNull { it?.let { t -> DIST.find(t)?.value?.replace(" ", "") } }

    private val DANGER = Regex("거절|끊기|종료|취소|중지|해제|Decline|Hang|End|Cancel|Stop|Dismiss", RegexOption.IGNORE_CASE)
    private val CONFIRM = Regex("받기|응답|수락|Answer|Accept", RegexOption.IGNORE_CASE)

    fun actionStyle(label: CharSequence): Act.Style = when {
        CONFIRM.containsMatchIn(label) -> Act.Style.CONFIRM
        DANGER.containsMatchIn(label) -> Act.Style.DANGER
        else -> Act.Style.NORMAL
    }

    /** Battery warning thresholds, highest first. */
    val LOW_LEVELS = intArrayOf(20, 10, 5)

    /** Lowest threshold newly reached going down, or null. [warned] is the last threshold already warned (101 = none). */
    fun lowBatteryCrossed(level: Int, warned: Int): Int? =
        LOW_LEVELS.filter { level <= it && warned > it }.minOrNull()
}
