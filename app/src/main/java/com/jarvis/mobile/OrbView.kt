package com.jarvis.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/** Duruma göre renk değiştiren, ses seviyesiyle nefes alan JARVIS küresi. */
class OrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private var smooth = 0f

    private fun withAlpha(color: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val base = min(w, h) * 0.2f
        t += 0.03f

        val state = JarvisState.state
        val color = when (state) {
            JState.IDLE -> Color.parseColor("#3C6E7A")
            JState.CONNECTING -> Color.parseColor("#7C8CFF")
            JState.LISTENING -> Color.parseColor("#00E5FF")
            JState.THINKING -> Color.parseColor("#FFB300")
            JState.SPEAKING -> Color.parseColor("#4FC3F7")
            JState.ERROR -> Color.parseColor("#FF5252")
        }
        val target = when (state) {
            JState.LISTENING, JState.SPEAKING -> JarvisState.level
            JState.THINKING -> 0.35f + 0.2f * sin(t * 4f)
            JState.CONNECTING -> 0.2f + 0.1f * sin(t * 3f)
            else -> 0.05f
        }
        smooth += (target - smooth) * 0.15f

        // dış parıltı
        val glowR = base * (1.6f + smooth * 1.4f)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(withAlpha(color, 150), withAlpha(color, 0)),
            floatArrayOf(0.25f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, paint)
        paint.shader = null

        // çekirdek
        paint.color = color
        canvas.drawCircle(cx, cy, base * (1f + smooth * 0.35f) + sin(t * 2f) * base * 0.03f, paint)

        // halkalar
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        for (i in 0..2) {
            val rr = base * (1.4f + i * 0.35f) + sin(t * 2f + i) * 6f + smooth * 14f * (i + 1)
            paint.color = withAlpha(color, 170 - i * 50)
            canvas.drawCircle(cx, cy, rr, paint)
        }

        // düşünürken dönen yay
        if (state == JState.THINKING || state == JState.CONNECTING) {
            val rr = base * 2.3f
            paint.strokeWidth = 6f
            paint.color = color
            canvas.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), t * 120f, 70f, false, paint)
        }
        postInvalidateOnAnimation()
    }
}
