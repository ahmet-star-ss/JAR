package com.jarvis.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Kalıcı hafıza: { kategori: { anahtar: değer } } biçiminde yerel JSON dosyası. */
object Memory {
    private fun file(c: Context) = File(c.filesDir, "memory.json")

    @Synchronized
    fun load(c: Context): JSONObject {
        return try {
            val f = file(c)
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    private fun store(c: Context, o: JSONObject) {
        file(c).writeText(o.toString())
    }

    @Synchronized
    fun save(c: Context, cat: String, key: String, value: String) {
        val all = load(c)
        var bucket = all.optJSONObject(cat)
        if (bucket == null) {
            bucket = JSONObject()
            all.put(cat, bucket)
        }
        bucket.put(key, value)
        store(c, all)
    }

    @Synchronized
    fun delete(c: Context, cat: String?, key: String?, match: String?): Int {
        val all = load(c)
        var n = 0
        val cats = all.keys().asSequence().toList()
        for (ct in cats) {
            if (!cat.isNullOrBlank() && ct != cat) continue
            val b = all.optJSONObject(ct) ?: continue
            for (k in b.keys().asSequence().toList()) {
                val v = b.optString(k)
                val hit = when {
                    !key.isNullOrBlank() -> k == key
                    !match.isNullOrBlank() -> k.contains(match, true) || v.contains(match, true)
                    else -> false
                }
                if (hit) {
                    b.remove(k)
                    n++
                }
            }
        }
        if (n > 0) store(c, all)
        return n
    }

    fun format(c: Context): String {
        val all = load(c)
        if (all.length() == 0) return ""
        val sb = StringBuilder("[KULLANICI HAKKINDA BİLGİLER]\n")
        for (ct in all.keys().asSequence().toList()) {
            val b = all.optJSONObject(ct) ?: continue
            for (k in b.keys().asSequence().toList()) {
                sb.append("- ").append(ct).append("/").append(k).append(": ").append(b.optString(k)).append("\n")
            }
        }
        return sb.toString()
    }
}
