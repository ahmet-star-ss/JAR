package com.jarvis.mobile

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.SearchManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Gemini'nin çağırabildiği telefon araçları: tanımlar + çalıştırıcı. */
class Tools(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val tr = Locale("tr")

    // ───────────────────────── Araç tanımları ─────────────────────────

    private fun p(name: String, type: String, desc: String, vararg enumVals: String): Pair<String, JSONObject> {
        val o = JSONObject().put("type", type).put("description", desc)
        if (enumVals.isNotEmpty()) o.put("enum", JSONArray(enumVals.toList()))
        return Pair(name, o)
    }

    private fun fn(
        name: String,
        desc: String,
        props: List<Pair<String, JSONObject>> = emptyList(),
        required: List<String> = emptyList()
    ): JSONObject {
        val f = JSONObject().put("name", name).put("description", desc)
        if (props.isNotEmpty()) {
            val po = JSONObject()
            for (pr in props) po.put(pr.first, pr.second)
            val params = JSONObject().put("type", "OBJECT").put("properties", po)
            if (required.isNotEmpty()) params.put("required", JSONArray(required))
            f.put("parameters", params)
        }
        return f
    }

    fun declarations(): JSONArray {
        val a = JSONArray()
        a.put(fn("get_weather", "Bir şehrin güncel hava durumunu ve 3 günlük tahmini verir.",
            listOf(p("location", "STRING", "Şehir adı, örn. Istanbul"))))
        a.put(fn("open_app", "Telefondaki bir uygulamayı adıyla açar.",
            listOf(p("app_name", "STRING", "Uygulama adı, örn. WhatsApp")), listOf("app_name")))
        a.put(fn("play_media", "YouTube veya Spotify'da şarkı/video arar ve oynatır.",
            listOf(p("query", "STRING", "Şarkı veya video adı"),
                p("provider", "STRING", "Sağlayıcı", "youtube", "spotify")), listOf("query")))
        a.put(fn("control_media", "Çalan medyayı durdurur, devam ettirir veya parça değiştirir.",
            listOf(p("action", "STRING", "İşlem", "pause", "resume", "next", "previous", "stop")), listOf("action")))
        a.put(fn("browser_control", "Tarayıcıda adres açar veya Google'da arama yapar.",
            listOf(p("action", "STRING", "İşlem", "open_url", "search"),
                p("url", "STRING", "Açılacak adres"),
                p("query", "STRING", "Arama ifadesi")), listOf("action")))
        a.put(fn("send_whatsapp_message", "WhatsApp'ta mesaj hazırlar veya gönderir.",
            listOf(p("recipient_name", "STRING", "Rehberdeki kişi adı"),
                p("phone", "STRING", "Telefon numarası (biliniyorsa)"),
                p("message", "STRING", "Mesaj metni"),
                p("send_now", "BOOLEAN", "true ise gönderir, false ise sadece hazırlar")), listOf("message")))
        a.put(fn("call_phone", "Bir kişiyi veya numarayı arar.",
            listOf(p("recipient_name", "STRING", "Rehberdeki kişi adı"),
                p("phone", "STRING", "Telefon numarası"))))
        a.put(fn("send_sms", "SMS gönderir.",
            listOf(p("recipient_name", "STRING", "Rehberdeki kişi adı"),
                p("phone", "STRING", "Telefon numarası"),
                p("message", "STRING", "Mesaj metni")), listOf("message")))
        a.put(fn("set_alarm", "Belirli saate alarm kurar.",
            listOf(p("hour", "INTEGER", "Saat 0-23"), p("minute", "INTEGER", "Dakika 0-59"),
                p("label", "STRING", "Alarm etiketi")), listOf("hour", "minute")))
        a.put(fn("set_timer", "Geri sayım zamanlayıcısı kurar.",
            listOf(p("seconds", "INTEGER", "Süre (saniye)"), p("label", "STRING", "Etiket")), listOf("seconds")))
        a.put(fn("add_reminder", "Belirli bir zamanda bildirim verecek anımsatıcı ekler.",
            listOf(p("title", "STRING", "Anımsatıcı metni"),
                p("due_iso", "STRING", "Yerel ISO 8601 tarih/saat, örn. 2026-10-19T09:00:00")),
            listOf("title", "due_iso")))
        a.put(fn("add_calendar_event", "Takvime etkinlik ekler.",
            listOf(p("title", "STRING", "Etkinlik başlığı"),
                p("start_iso", "STRING", "Başlangıç, yerel ISO 8601"),
                p("end_iso", "STRING", "Bitiş, yerel ISO 8601 (yoksa 1 saat)")), listOf("title", "start_iso")))
        a.put(fn("get_calendar_events", "Takvimdeki etkinlikleri listeler.",
            listOf(p("query", "STRING", "Aralık", "today", "tomorrow", "week", "month", "next"))))
        a.put(fn("delete_calendar_event", "Takvimden başlığa göre etkinlik siler.",
            listOf(p("title", "STRING", "Etkinlik başlığı"),
                p("start_iso", "STRING", "Başlangıç zamanı (belirsizlik varsa)")), listOf("title")))
        a.put(fn("get_notifications", "Son gelen bildirimleri okur.",
            listOf(p("limit", "INTEGER", "Kaç bildirim (varsayılan 8)"))))
        a.put(fn("sys_info", "Pil, saat, depolama, bellek veya ağ bilgisi verir.",
            listOf(p("kind", "STRING", "Bilgi türü", "battery", "time", "storage", "memory", "network", "all"))))
        a.put(fn("set_volume", "Ses seviyesini ayarlar.",
            listOf(p("level", "INTEGER", "0-100"),
                p("stream", "STRING", "Ses türü", "music", "ring", "alarm")), listOf("level")))
        a.put(fn("flashlight", "El fenerini açar veya kapatır.",
            listOf(p("on", "BOOLEAN", "true açar, false kapatır")), listOf("on")))
        a.put(fn("open_settings", "Telefon ayarlarının bir sayfasını açar.",
            listOf(p("page", "STRING", "Sayfa", "wifi", "bluetooth", "display", "sound", "battery", "apps", "location", "accessibility", "general")),
            listOf("page")))
        a.put(fn("phone_control", "Ekrandaki herhangi bir uygulamayı kontrol eder: ekranı okur, dokunur, yazar, kaydırır, geri/ana ekran tuşlarına basar.",
            listOf(p("action", "STRING", "Eylem", "read_screen", "tap_text", "tap_xy", "type_text", "scroll", "swipe",
                "back", "home", "recents", "notifications", "quick_settings", "lock_screen", "screenshot"),
                p("text", "STRING", "tap_text için ekrandaki yazı, type_text için yazılacak metin"),
                p("x", "INTEGER", "tap_xy için x"), p("y", "INTEGER", "tap_xy için y"),
                p("direction", "STRING", "scroll: içeriğin gideceği yön (down=aşağıdaki içeriği göster); swipe: parmak yönü",
                    "up", "down", "left", "right")), listOf("action")))
        a.put(fn("analyze_screen", "Ekranda şu an görünen yazıları ve öğeleri okuyup verir.",
            listOf(p("query", "STRING", "Ekranla ilgili soru"))))
        a.put(fn("save_memory", "Kullanıcı hakkındaki kalıcı bir bilgiyi kaydeder.",
            listOf(p("category", "STRING", "Kategori", "identity", "preferences", "notes"),
                p("key", "STRING", "Kısa anahtar"), p("value", "STRING", "Değer")), listOf("category", "key", "value")))
        a.put(fn("delete_memory", "Kalıcı hafızadan kayıt siler.",
            listOf(p("category", "STRING", "Kategori"), p("key", "STRING", "Anahtar"),
                p("match_text", "STRING", "Kayıtta geçen ifade"))))
        return a
    }

    // ───────────────────────── Çalıştırıcı ─────────────────────────

    fun run(name: String, a: JSONObject): String {
        return try {
            when (name) {
                "get_weather" -> weather(a.optString("location").trim())
                "open_app" -> openApp(a.optString("app_name").trim())
                "play_media" -> playMedia(a.optString("query").trim(), a.optString("provider", "youtube"))
                "control_media" -> controlMedia(a.optString("action"))
                "browser_control" -> browser(a.optString("action"), a.optString("url").trim(), a.optString("query").trim())
                "send_whatsapp_message" -> whatsapp(a)
                "call_phone" -> call(a)
                "send_sms" -> sms(a)
                "set_alarm" -> alarm(a.optInt("hour", -1), a.optInt("minute", -1), a.optString("label"))
                "set_timer" -> timer(a.optInt("seconds", -1), a.optString("label"))
                "add_reminder" -> addReminder(a.optString("title").trim(), a.optString("due_iso").trim())
                "add_calendar_event" -> addEvent(a.optString("title").trim(), a.optString("start_iso").trim(), a.optString("end_iso").trim())
                "get_calendar_events" -> getEvents(a.optString("query", "today"))
                "delete_calendar_event" -> deleteEvent(a.optString("title").trim(), a.optString("start_iso").trim())
                "get_notifications" -> notifications(a.optInt("limit", 8))
                "sys_info" -> sysInfo(a.optString("kind", "all"))
                "set_volume" -> volume(a.optInt("level", -1), a.optString("stream", "music"))
                "flashlight" -> flashlight(a.optBoolean("on", true))
                "open_settings" -> openSettings(a.optString("page"))
                "phone_control" -> phoneControl(a)
                "analyze_screen" -> {
                    val svc = JarvisAccessibilityService.instance
                    svc?.readScreen() ?: "Erişilebilirlik servisi kapalı. Ayarlar > Erişilebilirlik > JARVIS'i aç."
                }
                "save_memory" -> saveMemory(a)
                "delete_memory" -> deleteMemory(a)
                else -> "Bilinmeyen araç: $name"
            }
        } catch (e: SecurityException) {
            "İzin hatası: ${e.message}"
        } catch (e: Exception) {
            "Hata: ${e.message}"
        }
    }

    // ───────────────────────── Yardımcılar ─────────────────────────

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun hasPerm(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun launch(i: Intent): Boolean {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val host: Context = JarvisAccessibilityService.instance ?: ctx
            host.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun norm(s: String): String =
        s.lowercase(tr)
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
            .filter { it.isLetterOrDigit() }

    private fun getJson(url: String): JSONObject? {
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}") else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isInstalled(pkg: String): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ───────────────────────── Hava durumu ─────────────────────────

    private fun wmo(c: Int): String = when (c) {
        0 -> "açık"
        1, 2 -> "az bulutlu"
        3 -> "kapalı"
        45, 48 -> "sisli"
        in 51..57 -> "çiseleme"
        in 61..67 -> "yağmurlu"
        in 71..77 -> "karlı"
        in 80..82 -> "sağanak yağışlı"
        in 85..86 -> "kar sağanaklı"
        in 95..99 -> "gök gürültülü fırtınalı"
        else -> "değişken"
    }

    private fun weather(loc: String): String {
        val place = if (loc.isBlank()) Prefs.city(ctx) else loc
        val g = getJson("https://geocoding-api.open-meteo.com/v1/search?name=${enc(place)}&count=1&language=tr")
            ?: return "Hava servisine ulaşılamadı."
        val res = g.optJSONArray("results")?.optJSONObject(0) ?: return "$place bulunamadı."
        val lat = res.optDouble("latitude")
        val lon = res.optDouble("longitude")
        val name = res.optString("name", place)
        val w = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3"
        ) ?: return "Hava verisi alınamadı."
        val cur = w.optJSONObject("current") ?: return "Hava verisi yok."
        val sb = StringBuilder()
        sb.append("$name şu an ${Math.round(cur.optDouble("temperature_2m"))}°C, ${wmo(cur.optInt("weather_code"))}")
        sb.append(" (hissedilen ${Math.round(cur.optDouble("apparent_temperature"))}°C, nem %${cur.optInt("relative_humidity_2m")}, rüzgar ${Math.round(cur.optDouble("wind_speed_10m"))} km/s). ")
        val d = w.optJSONObject("daily")
        if (d != null) {
            val mx = d.optJSONArray("temperature_2m_max")
            val mn = d.optJSONArray("temperature_2m_min")
            val rain = d.optJSONArray("precipitation_probability_max")
            val labels = listOf("Bugün", "Yarın", "Öbür gün")
            for (i in 0 until minOf(3, mx?.length() ?: 0)) {
                sb.append("${labels[i]}: ${Math.round(mn?.optDouble(i) ?: 0.0)}–${Math.round(mx?.optDouble(i) ?: 0.0)}°C, yağış ihtimali %${rain?.optInt(i) ?: 0}. ")
            }
        }
        return sb.toString()
    }

    // ───────────────────────── Uygulama / medya / web ─────────────────────────

    private val aliasGroups = listOf(
        listOf("settings", "ayarlar"), listOf("camera", "kamera"), listOf("gallery", "galeri"),
        listOf("calculator", "hesapmakinesi", "hesap"), listOf("clock", "saat"),
        listOf("contacts", "kisiler", "rehber"), listOf("phone", "telefon"),
        listOf("messages", "mesajlar"), listOf("files", "dosyalar")
    )

    private fun openApp(name: String): String {
        if (name.isBlank()) return "Uygulama adı gerekli."
        val first = norm(name)
        if (first.isEmpty()) return "Uygulama adı anlaşılmadı."
        val wants = aliasGroups.firstOrNull { it.contains(first) } ?: listOf(first)
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(q, 0)
        var best: ResolveInfo? = null
        var bestScore = 0
        for (ri in list) {
            val label = norm(ri.loadLabel(pm).toString())
            val pkg = ri.activityInfo.packageName.lowercase()
            for (w in wants) {
                val score = when {
                    label == w -> 100
                    label.startsWith(w) -> 80
                    label.contains(w) -> 60
                    pkg.contains(w) -> 40
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = ri
                }
            }
        }
        val b = best ?: return "\"$name\" adında bir uygulama bulamadım."
        val li = pm.getLaunchIntentForPackage(b.activityInfo.packageName) ?: return "Uygulama açılamadı."
        val label = b.loadLabel(pm)
        return if (launch(li)) "$label açıldı." else
            "Açılamadı. 'Diğer uygulamaların üzerinde göster' iznini ve arka plan açma iznini kontrol et."
    }

    private fun playMedia(query: String, provider: String): String {
        if (query.isBlank()) return "Ne çalayım?"
        if (provider.equals("spotify", true)) {
            val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            i.setPackage("com.spotify.music")
            i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            i.putExtra(SearchManager.QUERY, query)
            if (launch(i)) return "Spotify'da \"$query\" çalınıyor."
            val f = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${enc(query)}"))
            return if (launch(f)) "Spotify'da arama açıldı." else "Spotify açılamadı."
        }
        val url = "https://www.youtube.com/results?search_query=${enc(query)}"
        val yt = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        yt.setPackage("com.google.android.youtube")
        var ok = launch(yt)
        if (!ok) ok = launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        if (!ok) return "YouTube açılamadı."
        val svc = JarvisAccessibilityService.instance
        if (svc != null) {
            try { Thread.sleep(3500) } catch (e: InterruptedException) {}
            val tapped = svc.tapFirstMatching(listOf("videoyu oynat", "play video", "dakika", "minutes", "saniye", "seconds"))
            return if (tapped) "YouTube'da \"$query\" açıldı." else "YouTube araması açıldı, ilk videoya dokunamadım."
        }
        return "YouTube araması açıldı (otomatik oynatma için Erişilebilirliği aç)."
    }

    private fun controlMedia(action: String): String {
        val code = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "Geçersiz medya işlemi."
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Tamam."
    }

    private fun browser(action: String, url: String, query: String): String {
        val target: String = when (action) {
            "search" -> {
                if (query.isBlank()) return "Ne arayayım?"
                "https://www.google.com/search?q=${enc(query)}"
            }
            "play_youtube" -> return playMedia(query, "youtube")
            else -> {
                if (url.isBlank()) return "Adres gerekli."
                if (url.startsWith("http")) url else "https://$url"
            }
        }
        return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(target)))) "Tarayıcıda açıldı." else "Tarayıcı açılamadı."
    }

    // ───────────────────────── İletişim ─────────────────────────

    private fun findNumber(name: String): String? {
        if (name.isBlank() || !hasPerm(Manifest.permission.READ_CONTACTS)) return null
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null
        val want = norm(name)
        var best: String? = null
        var score = 0
        c.use {
            while (it.moveToNext()) {
                val n = norm(it.getString(0) ?: "")
                val num = it.getString(1) ?: continue
                val s = when {
                    n == want -> 100
                    n.startsWith(want) -> 80
                    n.contains(want) -> 60
                    else -> 0
                }
                if (s > score) {
                    score = s
                    best = num
                }
            }
        }
        return best
    }

    private fun intl(n: String): String {
        val trimmed = n.filter { it.isDigit() || it == '+' }
        if (trimmed.startsWith("+")) return trimmed.substring(1).filter { it.isDigit() }
        val d = trimmed.filter { it.isDigit() }
        return when {
            d.startsWith("00") -> d.substring(2)
            d.startsWith("0") && d.length == 11 -> "90" + d.substring(1)
            d.length == 10 -> "90$d"
            else -> d
        }
    }

    private fun resolvePhone(a: JSONObject): String? {
        val phone = a.optString("phone").trim()
        if (phone.isNotBlank()) return phone
        return findNumber(a.optString("recipient_name").trim())
    }

    private fun whatsapp(a: JSONObject): String {
        val msg = a.optString("message").trim()
        val name = a.optString("recipient_name").trim()
        val phone = resolvePhone(a)
            ?: return if (name.isBlank()) "Alıcı belirtilmedi." else "\"$name\" rehberde bulunamadı. Numarasını söyler misin?"
        val sendNow = a.optBoolean("send_now", true)
        val uri = Uri.parse("https://wa.me/${intl(phone)}?text=${enc(msg)}")
        val i = Intent(Intent.ACTION_VIEW, uri)
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(it) }
        if (pkg != null) i.setPackage(pkg)
        if (!launch(i)) return "WhatsApp açılamadı."
        if (!sendNow) return "Mesaj WhatsApp'ta hazırlandı, gönderilmedi."
        val svc = JarvisAccessibilityService.instance
            ?: return "Mesaj hazırlandı ama otomatik göndermek için Erişilebilirlik gerekli."
        return if (svc.clickWhatsAppSend(9000)) "Mesaj gönderildi." else "Mesaj hazırlandı ama gönder tuşuna basamadım."
    }

    private fun call(a: JSONObject): String {
        val phone = resolvePhone(a)?.replace(" ", "") ?: return "Numara bulunamadı."
        val direct = hasPerm(Manifest.permission.CALL_PHONE)
        val i = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
        return if (launch(i)) (if (direct) "Aranıyor." else "Çevirici açıldı.") else "Arama başlatılamadı."
    }

    @Suppress("DEPRECATION")
    private fun sms(a: JSONObject): String {
        val msg = a.optString("message").trim()
        if (msg.isBlank()) return "Mesaj boş."
        val phone = resolvePhone(a)?.replace(" ", "") ?: return "Alıcı numarası bulunamadı."
        if (!hasPerm(Manifest.permission.SEND_SMS)) return "SMS izni verilmemiş."
        val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sm.sendMultipartTextMessage(phone, null, sm.divideMessage(msg), null, null)
        return "SMS gönderildi."
    }

    // ───────────────────────── Alarm / zamanlayıcı ─────────────────────────

    private fun alarm(h: Int, m: Int, label: String): String {
        if (h !in 0..23 || m !in 0..59) return "Geçersiz saat."
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(i)) String.format(tr, "Alarm %02d:%02d için kuruldu.", h, m) else "Alarm kurulamadı."
    }

    private fun timer(sec: Int, label: String): String {
        if (sec <= 0) return "Geçersiz süre."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(i)) "Zamanlayıcı $sec saniyeye kuruldu." else "Zamanlayıcı kurulamadı."
    }

    // ───────────────────────── Takvim ─────────────────────────

    private fun parseIso(s: String): Long? {
        if (s.isBlank()) return null
        return try {
            java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun calendarId(): Long? {
        if (!hasPerm(Manifest.permission.WRITE_CALENDAR)) return null
        val c = ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf("500"),
            null
        ) ?: return null
        return c.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun insertEvent(title: String, start: Long, end: Long, alert: Boolean): String {
        val cid = calendarId() ?: return "Takvim izni yok veya yazılabilir takvim bulunamadı."
        val v = ContentValues()
        v.put(CalendarContract.Events.DTSTART, start)
        v.put(CalendarContract.Events.DTEND, end)
        v.put(CalendarContract.Events.TITLE, title)
        v.put(CalendarContract.Events.CALENDAR_ID, cid)
        v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return "Etkinlik eklenemedi."
        if (alert) {
            val id = uri.lastPathSegment?.toLongOrNull()
            if (id != null) {
                val r = ContentValues()
                r.put(CalendarContract.Reminders.EVENT_ID, id)
                r.put(CalendarContract.Reminders.MINUTES, 0)
                r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
            }
        }
        return "Eklendi: $title, " + SimpleDateFormat("d MMMM EEEE HH:mm", tr).format(Date(start))
    }

    private fun addReminder(title: String, dueIso: String): String {
        if (title.isBlank()) return "Anımsatıcı metni boş."
        val t = parseIso(dueIso) ?: return "Tarih/saat anlaşılamadı."
        return insertEvent(title, t, t + 5 * 60_000L, true)
    }

    private fun addEvent(title: String, startIso: String, endIso: String): String {
        if (title.isBlank()) return "Başlık boş."
        val s = parseIso(startIso) ?: return "Başlangıç zamanı anlaşılamadı."
        val e = parseIso(endIso) ?: (s + 60 * 60_000L)
        return insertEvent(title, s, e, false)
    }

    private fun getEvents(query: String): String {
        if (!hasPerm(Manifest.permission.READ_CALENDAR)) return "Takvim okuma izni verilmemiş."
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val day0 = cal.timeInMillis
        val day = 24 * 60 * 60_000L
        val from: Long
        val to: Long
        when (query) {
            "tomorrow" -> { from = day0 + day; to = day0 + 2 * day }
            "week" -> { from = now; to = now + 7 * day }
            "month" -> { from = now; to = now + 30 * day }
            "next" -> { from = now; to = now + 60 * day }
            else -> { from = day0; to = day0 + day }
        }
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from)
        ContentUris.appendId(b, to)
        val c = ctx.contentResolver.query(
            b.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        ) ?: return "Takvime erişilemedi."
        val fmt = SimpleDateFormat("d MMM EEE HH:mm", tr)
        val lines = ArrayList<String>()
        c.use {
            while (it.moveToNext() && lines.size < (if (query == "next") 1 else 15)) {
                lines.add("${fmt.format(Date(it.getLong(1)))} — ${it.getString(0) ?: "(başlıksız)"}")
            }
        }
        return if (lines.isEmpty()) "Bu aralıkta etkinlik yok." else lines.joinToString("\n")
    }

    private fun deleteEvent(title: String, startIso: String): String {
        if (!hasPerm(Manifest.permission.WRITE_CALENDAR) || !hasPerm(Manifest.permission.READ_CALENDAR)) return "Takvim izni yok."
        if (title.isBlank()) return "Başlık gerekli."
        val start = parseIso(startIso)
        val c = ctx.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DTSTART),
            "${CalendarContract.Events.TITLE} LIKE ? AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf("%$title%"),
            null
        ) ?: return "Takvime erişilemedi."
        val ids = ArrayList<Long>()
        c.use {
            while (it.moveToNext()) {
                val ds = it.getLong(1)
                if (start == null || Math.abs(ds - start) < 24 * 60 * 60_000L) ids.add(it.getLong(0))
            }
        }
        if (ids.isEmpty()) return "Eşleşen etkinlik bulunamadı."
        for (id in ids.take(3)) {
            ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        }
        return "${minOf(ids.size, 3)} etkinlik silindi."
    }

    // ───────────────────────── Sistem ─────────────────────────

    private fun notifications(limit: Int): String {
        if (!JarvisNotificationListener.connected) return "Bildirim erişimi kapalı. Ayarlardan JARVIS'e bildirim erişimi ver."
        val l = JarvisNotificationListener.snapshot(if (limit > 0) limit else 8)
        return if (l.isEmpty()) "Yeni bildirim yok." else l.joinToString("\n")
    }

    private fun sysInfo(kind: String): String {
        val out = ArrayList<String>()
        val all = kind.isBlank() || kind == "all"
        if (all || kind == "battery") {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lv = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val sc = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val st = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            out.add("Pil: %" + (if (lv >= 0 && sc > 0) lv * 100 / sc else -1) + (if (charging) " (şarjda)" else ""))
        }
        if (all || kind == "time") {
            out.add("Saat: " + SimpleDateFormat("d MMMM yyyy EEEE HH:mm", tr).format(Date()))
        }
        if (all || kind == "storage") {
            val s = StatFs(Environment.getDataDirectory().path)
            val gb = 1024.0 * 1024.0 * 1024.0
            out.add(String.format(tr, "Depolama: %.1f GB boş / %.1f GB", s.availableBytes / gb, s.totalBytes / gb))
        }
        if (all || kind == "memory") {
            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            val mb = 1024.0 * 1024.0
            out.add(String.format(tr, "Bellek: %.0f MB boş / %.0f MB", mi.availMem / mb, mi.totalMem / mb))
        }
        if (all || kind == "network") {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            out.add(
                "Ağ: " + when {
                    caps == null -> "bağlantı yok"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobil veri"
                    else -> "bağlı"
                }
            )
        }
        return if (out.isEmpty()) "Bilinmeyen bilgi türü." else out.joinToString("\n")
    }

    private fun volume(level: Int, stream: String): String {
        if (level !in 0..100) return "Ses seviyesi 0-100 arası olmalı."
        val st = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(st)
        am.setStreamVolume(st, Math.round(level / 100f * max), 0)
        return "Ses seviyesi %$level yapıldı."
    }

    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Fener bulunamadı."
        cm.setTorchMode(id, on)
        return if (on) "Fener açıldı." else "Fener kapatıldı."
    }

    private fun openSettings(page: String): String {
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (launch(Intent(action))) "Ayarlar açıldı." else "Ayarlar açılamadı."
    }

    // ───────────────────────── Telefon kontrolü ─────────────────────────

    private fun phoneControl(a: JSONObject): String {
        val svc = JarvisAccessibilityService.instance
            ?: return "Erişilebilirlik servisi kapalı. Ayarlar > Erişilebilirlik > JARVIS'i aç."
        val text = a.optString("text").trim()
        return when (a.optString("action")) {
            "read_screen" -> svc.readScreen()
            "tap_text" -> when {
                text.isEmpty() -> "Dokunulacak yazı gerekli."
                svc.tapText(text) -> "Dokunuldu: $text"
                else -> "\"$text\" ekranda bulunamadı."
            }
            "tap_xy" -> {
                val x = a.optInt("x", -1)
                val y = a.optInt("y", -1)
                if (x < 0 || y < 0) {
                    "x ve y gerekli."
                } else {
                    svc.tapXY(x.toFloat(), y.toFloat())
                    "Dokunuldu ($x,$y)."
                }
            }
            "type_text" -> if (svc.typeText(text)) "Yazıldı." else "Yazı alanı bulunamadı."
            "scroll" -> {
                // direction: içeriğin gideceği yön; parmak ters yöne gider
                val d = when (a.optString("direction", "down")) {
                    "up" -> "down"
                    "down" -> "up"
                    "left" -> "right"
                    else -> "left"
                }
                svc.swipe(d)
                "Kaydırıldı."
            }
            "swipe" -> {
                svc.swipe(a.optString("direction", "up"))
                "Kaydırıldı."
            }
            "back" -> { svc.global(AccessibilityService.GLOBAL_ACTION_BACK); "Geri." }
            "home" -> { svc.global(AccessibilityService.GLOBAL_ACTION_HOME); "Ana ekran." }
            "recents" -> { svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS); "Son uygulamalar." }
            "notifications" -> { svc.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); "Bildirim paneli açıldı." }
            "quick_settings" -> { svc.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS); "Hızlı ayarlar açıldı." }
            "lock_screen" -> { svc.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); "Ekran kilitlendi." }
            "screenshot" -> { svc.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT); "Ekran görüntüsü alındı." }
            else -> "Bilinmeyen eylem."
        }
    }

    // ───────────────────────── Hafıza ─────────────────────────

    private fun saveMemory(a: JSONObject): String {
        val cat = a.optString("category").trim().ifEmpty { "notes" }
        val key = a.optString("key").trim()
        val value = a.optString("value").trim()
        if (key.isEmpty() || value.isEmpty()) return "Anahtar ve değer gerekli."
        Memory.save(ctx, cat, key, value)
        return "Kaydedildi."
    }

    private fun deleteMemory(a: JSONObject): String {
        val n = Memory.delete(
            ctx,
            a.optString("category").trim().ifEmpty { null },
            a.optString("key").trim().ifEmpty { null },
            a.optString("match_text").trim().ifEmpty { null }
        )
        return if (n > 0) "$n kayıt silindi." else "Eşleşen kayıt yok."
    }
}
