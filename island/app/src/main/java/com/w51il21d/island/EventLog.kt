package com.w51il21d.island

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Last few things the island decided, for the status panel. App names only, never message text. */
object EventLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    @Synchronized fun add(s: String) {
        lines.addFirst("${fmt.format(Date())} $s")
        while (lines.size > 14) lines.removeLast()
    }

    @Synchronized fun recent(): List<String> = lines.toList()
}
