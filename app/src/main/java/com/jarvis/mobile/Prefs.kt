package com.jarvis.mobile

import android.content.Context

object Prefs {
    const val DEFAULT_MODEL = "models/gemini-2.5-flash-native-audio-latest"
    val VOICES = listOf("Charon", "Puck", "Aoede", "Kore", "Fenrir", "Leda", "Orus", "Zephyr")

    private fun sp(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("api_key", "") ?: ""
    fun setApiKey(c: Context, v: String) {
        sp(c).edit().putString("api_key", v.trim()).apply()
    }

    fun voice(c: Context): String = sp(c).getString("voice", "Charon") ?: "Charon"
    fun setVoice(c: Context, v: String) {
        sp(c).edit().putString("voice", v).apply()
    }

    fun model(c: Context): String = sp(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun city(c: Context): String = sp(c).getString("city", "Istanbul") ?: "Istanbul"
}
