package com.jarvis.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Diğer uygulamalarda ekranı okuma, dokunma, yazma ve kaydırma. */
class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ───────────── Okuma ─────────────

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Ekran okunamadı (içerik yok)."
        val out = StringBuilder()
        out.append("Uygulama: ").append(root.packageName?.toString() ?: "?").append("\n")
        var count = 0

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || count >= 120 || out.length > 5000) return
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val label = if (text.isNotEmpty()) text else desc
            if (n.isVisibleToUser && (label.isNotEmpty() || n.isEditable)) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    out.append("- ").append(label.take(100))
                    if (n.isEditable) out.append(" [yazı alanı]")
                    if (n.isClickable) out.append(" [tıklanabilir]")
                    out.append(" @").append(r.centerX()).append(",").append(r.centerY()).append("\n")
                    count++
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }

        walk(root)
        return if (count == 0) out.append("(okunabilir öğe yok)").toString() else out.toString()
    }

    private fun findNode(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) {
            val r = findNode(n.getChild(i), pred)
            if (r != null) return r
        }
        return null
    }

    // ───────────── Dokunma ─────────────

    fun tapText(q: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val found = root.findAccessibilityNodeInfosByText(q)
        var target: AccessibilityNodeInfo? = found?.firstOrNull { it.isVisibleToUser }
        if (target == null) {
            target = findNode(root) { n ->
                n.isVisibleToUser && (n.contentDescription?.toString() ?: "").contains(q, true)
            }
        }
        return if (target != null) tapNode(target) else false
    }

    fun tapFirstMatching(keys: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = findNode(root) { node ->
            val s = node.contentDescription?.toString() ?: node.text?.toString() ?: ""
            node.isVisibleToUser && keys.any { k -> s.contains(k, true) }
        }
        return if (n != null) tapNode(n) else false
    }

    private fun tapNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            hops++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        tapXY(r.exactCenterX(), r.exactCenterY())
        return true
    }

    fun tapXY(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        dispatchGesture(g, null, null)
    }

    /** dir: parmağın hareket yönü. */
    fun swipe(dir: String) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val p = Path()
        when (dir) {
            "up" -> { p.moveTo(w / 2, h * 0.75f); p.lineTo(w / 2, h * 0.25f) }
            "down" -> { p.moveTo(w / 2, h * 0.25f); p.lineTo(w / 2, h * 0.75f) }
            "left" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            else -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 350))
            .build()
        dispatchGesture(g, null, null)
    }

    fun typeText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findNode(root) { it.isEditable && it.isVisibleToUser }
            ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun global(code: Int): Boolean = performGlobalAction(code)

    // ───────────── WhatsApp "Gönder" ─────────────

    fun clickWhatsAppSend(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val root = rootInActiveWindow
            if (root != null && (root.packageName?.toString() ?: "").startsWith("com.whatsapp")) {
                var node: AccessibilityNodeInfo? = null
                for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
                    val l = root.findAccessibilityNodeInfosByViewId(id)
                    if (l != null && l.isNotEmpty()) {
                        node = l[0]
                        break
                    }
                }
                if (node == null) {
                    node = findNode(root) { n ->
                        val d = n.contentDescription?.toString()?.lowercase() ?: ""
                        d == "gönder" || d == "send"
                    }
                }
                if (node != null && tapNode(node)) return true
            }
            try { Thread.sleep(500) } catch (e: InterruptedException) { return false }
        }
        return false
    }
}
