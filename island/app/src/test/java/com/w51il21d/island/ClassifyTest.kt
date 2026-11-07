package com.w51il21d.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifyTest {
    @Test fun ongoingKinds() {
        assertEquals(Kind.CALL, Classify.ongoingKind("com.samsung.android.incallui", null, true, true))
        assertEquals(Kind.CALL, Classify.ongoingKind("x", "call", true, false))
        assertEquals(Kind.NAV, Classify.ongoingKind("com.skt.tmap.ku", null, true, false))
        assertEquals(Kind.NAV, Classify.ongoingKind("x", "navigation", true, false))
        assertEquals(Kind.TIMER, Classify.ongoingKind("com.sec.android.app.clockpackage", null, true, false))
        assertEquals(Kind.TIMER, Classify.ongoingKind("x", null, true, true))
        assertNull(Classify.ongoingKind("x", null, true, false))
        assertNull(Classify.ongoingKind("com.samsung.android.incallui", "call", false, false)) // missed call
    }

    @Test fun moreApps() {
        fun k(pkg: String = "x", cat: String? = null, ongoing: Boolean = true, chrono: Boolean = false,
              call: Boolean = false, progress: Boolean = false, promoted: Boolean = false) =
            Classify.ongoingKind(Classify.Signals(pkg, cat, ongoing, chrono, call, progress, promoted))
        assertEquals(Kind.CALL, k(pkg = "com.kakao.talk", call = true))          // voice call in a chat app
        assertEquals(Kind.LIVE, k(pkg = "com.sampleapp.delivery", progress = true))     // delivery with a progress bar
        assertEquals(Kind.LIVE, k(pkg = "com.example", ongoing = false, promoted = true)) // Android 16 live update
        assertNull(k(pkg = "android", progress = true))                           // system noise
        assertNull(k(pkg = "com.example"))                                        // plain foreground service
        assertEquals(Kind.NAV, k(pkg = "kr.mappers.AtlanSmart"))
        assertEquals("12분", Classify.shortEta("약 12분 후 도착"))
        assertEquals("12분", Classify.shortEta(null, "도착까지 12 분"))
        assertNull(Classify.shortEta("주문 접수"))
    }

    private fun alert(
        ongoing: Boolean = false, summary: Boolean = false, importance: Int = 3, dnd: Boolean = true,
        repeat: Boolean = false, title: Boolean = true, excluded: Boolean = false, front: Boolean = false,
    ) = Classify.shouldAlert(ongoing, summary, importance, dnd, repeat, title, excluded, front)

    @Test fun alertRules() {
        assertTrue(alert())
        assertFalse(alert(ongoing = true))
        assertFalse(alert(summary = true))
        assertFalse(alert(importance = 2))
        assertFalse(alert(dnd = false))
        assertFalse(alert(repeat = true))
        assertFalse(alert(title = false))
        assertFalse(alert(excluded = true))
        assertFalse(alert(front = true))
    }

    @Test fun textExtraction() {
        assertEquals("04:32", Classify.shortTime("타이머", "04:32 남음"))
        assertEquals("1:02:03", Classify.shortTime("1:02:03"))
        assertNull(Classify.shortTime("오후 회의", null))
        assertEquals("300m", Classify.shortDistance("300 m 후 우회전"))
        assertEquals("1.2km", Classify.shortDistance(null, "1.2km 직진"))
        assertNull(Classify.shortDistance("목적지 도착"))
    }

    @Test fun actionStyles() {
        assertEquals(Act.Style.DANGER, Classify.actionStyle("거절"))
        assertEquals(Act.Style.DANGER, Classify.actionStyle("통화 끊기"))
        assertEquals(Act.Style.CONFIRM, Classify.actionStyle("받기"))
        assertEquals(Act.Style.NORMAL, Classify.actionStyle("답장"))
    }

    @Test fun lowBattery() {
        assertNull(Classify.lowBatteryCrossed(50, 101))
        assertEquals(20, Classify.lowBatteryCrossed(20, 101))
        assertNull(Classify.lowBatteryCrossed(19, 20))
        assertEquals(10, Classify.lowBatteryCrossed(9, 20))
        assertEquals(5, Classify.lowBatteryCrossed(4, 101))
    }

    @Test fun selection() {
        val media = Item("m", Kind.MEDIA, "song", seq = 1)
        val call = Item("c", Kind.CALL, "call", seq = 2)
        val timerOld = Item("t1", Kind.TIMER, "t", seq = 3)
        val timerNew = Item("t2", Kind.TIMER, "t", seq = 4)
        assertEquals("c", Selection.pick(listOf(media, call, timerOld))!!.key)
        assertEquals("t2", Selection.pick(listOf(media, timerOld, timerNew))!!.key)
        assertNull(Selection.pick(emptyList()))
    }
}
