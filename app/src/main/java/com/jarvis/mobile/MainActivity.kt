package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val bg = Color.parseColor("#05080F")
    private val cyan = Color.parseColor("#00E5FF")

    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btn: Button
    private val listener: () -> Unit = { refresh() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.setPadding(dp(16), dp(36), dp(16), dp(20))

        val title = TextView(this)
        title.text = "J.A.R.V.I.S"
        title.setTextColor(cyan)
        title.textSize = 22f
        title.letterSpacing = 0.3f
        title.gravity = Gravity.CENTER
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        orb = OrbView(this)
        root.addView(orb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 16f
        status.gravity = Gravity.CENTER
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        log = TextView(this)
        log.setTextColor(Color.parseColor("#B0BEC5"))
        log.textSize = 13f
        log.setPadding(dp(8), dp(8), dp(8), dp(8))
        scroll = ScrollView(this)
        scroll.addView(log)
        val sp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        sp.topMargin = dp(10)
        root.addView(scroll, sp)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        btn = styledButton("BAŞLAT", cyan, Color.BLACK)
        val bp = LinearLayout.LayoutParams(0, dp(56), 1f)
        bp.rightMargin = dp(8)
        row.addView(btn, bp)
        val cfg = styledButton("AYARLAR", Color.parseColor("#1B2733"), Color.WHITE)
        row.addView(cfg, LinearLayout.LayoutParams(dp(120), dp(56)))
        val rp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        rp.topMargin = dp(12)
        root.addView(row, rp)

        setContentView(root)

        btn.setOnClickListener {
            val s = JarvisState.state
            if (s == JState.IDLE || s == JState.ERROR) startAssistant() else JarvisService.stop(this)
        }
        cfg.setOnClickListener { showSettings() }

        if (Prefs.apiKey(this).isBlank()) askApiKey(null)
    }

    private fun styledButton(label: String, fill: Int, textColor: Int): Button {
        val b = Button(this)
        b.text = label
        b.setTextColor(textColor)
        b.isAllCaps = false
        b.textSize = 15f
        val gd = GradientDrawable()
        gd.setColor(fill)
        gd.cornerRadius = dp(28).toFloat()
        b.background = gd
        return b
    }

    override fun onResume() {
        super.onResume()
        JarvisState.addListener(listener)
        refresh()
    }

    override fun onPause() {
        JarvisState.removeListener(listener)
        super.onPause()
    }

    private fun refresh() {
        val s = JarvisState.state
        status.text = when (s) {
            JState.IDLE -> "Hazır"
            JState.CONNECTING -> "Bağlanıyor…"
            JState.LISTENING -> "Dinliyorum"
            JState.THINKING -> "İşlem yapıyor…"
            JState.SPEAKING -> "Konuşuyor"
            JState.ERROR -> "Hata: " + JarvisState.message
        }
        log.text = JarvisState.transcript()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        btn.text = if (s == JState.IDLE || s == JState.ERROR) "BAŞLAT" else "DURDUR"
    }

    // ───────────── Başlatma ve izinler ─────────────

    private fun wantedPermissions(): List<String> {
        val l = arrayListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun startAssistant() {
        if (Prefs.apiKey(this).isBlank()) {
            askApiKey { startAssistant() }
            return
        }
        val missing = wantedPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        JarvisState.log("— Oturum başlatılıyor —")
        JarvisService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            JarvisService.start(this)
        } else {
            Toast.makeText(this, "Mikrofon izni olmadan çalışamam.", Toast.LENGTH_LONG).show()
        }
    }

    // ───────────── Ayarlar ─────────────

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    private fun showSettings() {
        val items = arrayOf(
            "Gemini API anahtarı  " + mark(Prefs.apiKey(this).isNotBlank()),
            "Ses: " + Prefs.voice(this),
            "Erişilebilirlik (telefon kontrolü)  " + mark(JarvisAccessibilityService.instance != null),
            "Bildirim erişimi  " + mark(JarvisNotificationListener.connected),
            "Diğer uygulamaların üzerinde göster  " + mark(Settings.canDrawOverlays(this)),
            "Pil kısıtlamasını kaldır",
            "Uygulama bilgisi (Kısıtlı ayarlara izin ver)"
        )
        AlertDialog.Builder(this)
            .setTitle("Ayarlar")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askApiKey(null)
                    1 -> pickVoice()
                    2 -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    3 -> open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    4 -> open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    5 -> open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    else -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Ayar sayfası açılamadı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickVoice() {
        val voices = Prefs.VOICES.toTypedArray()
        val cur = Prefs.VOICES.indexOf(Prefs.voice(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Ses seç (sonraki oturumda geçerli)")
            .setSingleChoiceItems(voices, cur) { d, which ->
                Prefs.setVoice(this, voices[which])
                d.dismiss()
            }
            .show()
    }

    private fun askApiKey(then: (() -> Unit)?) {
        val et = EditText(this)
        et.hint = "AIza..."
        et.setSingleLine(true)
        et.setText(Prefs.apiKey(this))
        AlertDialog.Builder(this)
            .setTitle("Gemini API anahtarı")
            .setMessage("aistudio.google.com/apikey adresinden ücretsiz alabilirsin.")
            .setView(et)
            .setPositiveButton("Kaydet") { _, _ ->
                Prefs.setApiKey(this, et.text.toString())
                then?.invoke()
            }
            .setNegativeButton("İptal", null)
            .show()
    }
}
