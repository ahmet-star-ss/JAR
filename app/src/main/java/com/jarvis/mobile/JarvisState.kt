package com.jarvis.mobile

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

enum class JState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

object JarvisState {
    @Volatile var state: JState = JState.IDLE
    @Volatile var level: Float = 0f
    @Volatile var message: String = ""

    private val lines = ArrayList<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun set(s: JState, msg: String = "") {
        state = s
        message = msg
        if (s == JState.IDLE || s == JState.ERROR) level = 0f
        fire()
    }

    fun log(line: String) {
        synchronized(lines) {
            lines.add(line)
            while (lines.size > 40) lines.removeAt(0)
        }
        fire()
    }

    fun transcript(): String = synchronized(lines) { lines.joinToString("\n\n") }

    private fun fire() {
        main.post { for (l in listeners) l() }
    }
}
