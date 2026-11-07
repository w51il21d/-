package com.w51il21d.island

/** Pure rules for turning a notification into an island item. Kept free of Android calls so they are unit-tested. */
object Classify {
    val NAV_PKGS = setOf(
        "com.google.android.apps.maps", "com.skt.tmap.ku", "com.skt.skaf.l001mtm091",
        "com.locnall.KimGiSa", "com.nhn.android.nmap", "net.daum.android.map", "com.waze",
    )
    val CALL_PKGS = setOf(
        "com.samsung.android.incallui", "com.android.incallui", "com.google.android.dialer", "com.skt.prod.dialer",
    )
    val CLOCK_PKGS = setOf("com.sec.android.app.clockpackage", "com.google.android.deskclock")

    fun ongoingKind(pkg: String, category: String?, ongoing: Boolean, chronometer: Boolean): Kind? {
        if (!ongoing) return null
        return when {
            category == "call" || pkg in CALL_PKGS -> Kind.CALL
            category == "navigation" || pkg in NAV_PKGS -> Kind.NAV
            category == "stopwatch" || category == "alarm" || pkg in CLOCK_PKGS -> Kind.TIMER
            chronometer -> Kind.TIMER
            else -> null
        }
    }

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
