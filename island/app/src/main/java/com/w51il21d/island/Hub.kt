package com.w51il21d.island

import android.os.Handler
import android.os.Looper

/**
 * Single place that knows what should be on the island. Everything runs on the main thread:
 * notification callbacks, media callbacks and broadcast receivers all arrive there.
 */
object Hub {
    private val h = Handler(Looper.getMainLooper())
    private val live = LinkedHashMap<String, Item>()
    private val queue = ArrayDeque<Item>()
    var alert: Item? = null
        private set
    private var holding = false
    private var seq = 0L
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun putLive(item: Item) {
        val prev = live[item.key]
        live[item.key] = item.copy(seq = prev?.seq ?: ++seq)
        changed()
    }

    fun removeLive(key: String) { if (live.remove(key) != null) changed() }

    fun removeLiveKinds(kinds: Set<Kind>) {
        if (live.values.removeAll { it.kind in kinds }) changed()
    }

    fun liveItems(): List<Item> = live.values.toList()

    fun alert(item: Item) {
        val a = item.copy(seq = ++seq)
        val cur = alert
        when {
            cur == null || cur.key == a.key -> show(a)
            else -> {
                queue.removeAll { it.key == a.key }
                queue.addLast(a)
                while (queue.size > 4) queue.removeFirst()
            }
        }
    }

    private val expire = Runnable { next() }

    private fun show(a: Item) {
        alert = a
        h.removeCallbacks(expire)
        if (!holding) h.postDelayed(expire, a.durationMs)
        changed()
    }

    private fun next() {
        h.removeCallbacks(expire)
        val n = queue.removeFirstOrNull()
        if (n != null) show(n) else if (alert != null) { alert = null; changed() }
    }

    fun dismissAlert() { holding = false; next() }

    /** While the user has the island expanded, the current alert must not time out. */
    fun hold(on: Boolean) {
        if (holding == on) return
        holding = on
        h.removeCallbacks(expire)
        if (!on && alert != null) h.postDelayed(expire, 2500)
    }

    fun current(): Item? = alert ?: Selection.pick(live.values)

    private fun changed() { for (l in listeners.toList()) l() }

    /** Tests only. */
    fun reset() {
        h.removeCallbacks(expire); live.clear(); queue.clear(); alert = null; holding = false; listeners.clear()
    }
}
