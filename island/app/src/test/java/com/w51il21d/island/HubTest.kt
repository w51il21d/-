package com.w51il21d.island

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class HubTest {
    @Before fun setUp() = Hub.reset()
    @After fun tearDown() = Hub.reset()

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun alertsQueueAndExpire() {
        Hub.alert(Item("a", Kind.ALERT, "A", durationMs = 3000))
        Hub.alert(Item("b", Kind.ALERT, "B", durationMs = 2000))
        assertEquals("a", Hub.current()!!.key)
        idle(3001)
        assertEquals("b", Hub.current()!!.key)
        idle(2001)
        assertNull(Hub.current())
    }

    @Test fun sameKeyReplacesInPlace() {
        Hub.alert(Item("a", Kind.ALERT, "A1", durationMs = 3000))
        Hub.alert(Item("a", Kind.ALERT, "A2", durationMs = 3000))
        assertEquals("A2", Hub.current()!!.title)
        idle(3001)
        assertNull(Hub.current())
    }

    @Test fun holdKeepsAlertUntilReleased() {
        Hub.alert(Item("a", Kind.ALERT, "A", durationMs = 1000))
        Hub.hold(true)
        idle(60_000)
        assertEquals("a", Hub.current()!!.key)
        Hub.hold(false)
        idle(2501)
        assertNull(Hub.current())
    }

    @Test fun dismissWhileHeldStillExpiresNext() {
        Hub.alert(Item("a", Kind.ALERT, "A", durationMs = 1000))
        Hub.alert(Item("b", Kind.ALERT, "B", durationMs = 1000))
        Hub.hold(true)
        Hub.dismissAlert()
        assertEquals("b", Hub.current()!!.key)
        idle(1001)
        assertNull(Hub.current())
    }

    @Test fun clearedNotificationLeavesIsland() {
        Hub.alert(Item("n:1", Kind.ALERT, "A", durationMs = 5000))
        Hub.alert(Item("n:2", Kind.ALERT, "B", durationMs = 5000))
        Hub.removeAlert("n:2")
        Hub.removeAlert("n:1")
        assertNull(Hub.current())
    }

    @Test fun liveItemsByPriorityAndAlertOnTop() {
        Hub.putLive(Item("media", Kind.MEDIA, "song"))
        Hub.putLive(Item("call", Kind.CALL, "엄마"))
        assertEquals("call", Hub.current()!!.key)
        Hub.alert(Item("n", Kind.ALERT, "msg", durationMs = 1000))
        assertEquals("n", Hub.current()!!.key)
        idle(1001)
        Hub.removeLive("call")
        assertEquals("media", Hub.current()!!.key)
    }
}
