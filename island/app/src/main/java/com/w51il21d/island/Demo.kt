package com.w51il21d.island

import android.content.Context

/** Sample items for the preview buttons and for screenshot tests. */
object Demo {
    fun notificationItem(c: Context) = Item(
        key = "demo:n", kind = Kind.ALERT, title = "김지민",
        text = "오늘 저녁 7시에 강남역 11번 출구에서 봐요!", label = "메시지",
        icon = Ui.glyph(c, R.drawable.ic_notify, Ui.GREEN), accent = Ui.GREEN,
        actions = listOf(Act("읽음 표시") {}, Act("답장") {}),
        shape = Shape.BANNER, durationMs = 5000,
    )

    fun chargingItem(c: Context) = Item(
        key = "demo:charge", kind = Kind.ALERT, title = "충전 중", text = "완전 충전까지 약 1시간 5분", label = "배터리",
        icon = Ui.glyph(c, R.drawable.ic_bolt, Ui.GREEN), accent = Ui.GREEN, rightText = "57%", rightColor = Ui.GREEN, durationMs = 3000,
    )

    fun timerItem(c: Context) = Item(
        key = "demo:timer", kind = Kind.TIMER, title = "타이머", text = "라면", label = "시계",
        icon = Ui.glyph(c, R.drawable.ic_timer, Ui.ORANGE), accent = Ui.ORANGE,
        chronoBase = System.currentTimeMillis() + 3 * 60_000 + 1000, countDown = true,
        actions = listOf(Act("일시정지") {}, Act("취소", Act.Style.DANGER) { Hub.removeLive("demo:timer") }),
    )

    fun notification(c: Context) = Hub.alert(notificationItem(c))
    fun charging(c: Context) = Hub.alert(chargingItem(c))

    /** Shows a 3-minute timer for 20 seconds. */
    fun timer(c: Context) {
        Hub.putLive(timerItem(c))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Hub.removeLive("demo:timer") }, 20_000)
    }
}
