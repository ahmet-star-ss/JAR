package com.jarvis.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Gemini Live API (WebSocket) istemcisi: mikrofon 16 kHz PCM gönderir,
 * 24 kHz PCM yanıt çalar, araç çağrılarını Tools ile çalıştırır.
 */
class GeminiLive(private val ctx: Context, private val tools: Tools) {
    var onEnd: (() -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private val toolExec = Executors.newSingleThreadExecutor()
    private val playQueue = LinkedBlockingQueue<ByteArray>()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var ready = false
    private var retries = 0

    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var micThread: Thread? = null
    private var playThread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    private val userBuf = StringBuilder()
    private val aiBuf = StringBuilder()

    fun start() {
        if (Prefs.apiKey(ctx).isBlank()) {
            fail("Gemini API anahtarı girilmemiş")
            return
        }
        running = true
        JarvisState.set(JState.CONNECTING, "Bağlanıyor…")
        startAudio()
        connect()
    }

    fun stop(keepError: Boolean = false) {
        running = false
        ready = false
        try { ws?.close(1000, "bye") } catch (e: Exception) {}
        ws = null
        micThread?.interrupt()
        playThread?.interrupt()
        try { rec?.stop() } catch (e: Exception) {}
        try { rec?.release() } catch (e: Exception) {}
        rec = null
        try { aec?.release() } catch (e: Exception) {}
        try { ns?.release() } catch (e: Exception) {}
        try { track?.stop() } catch (e: Exception) {}
        try { track?.release() } catch (e: Exception) {}
        track = null
        playQueue.clear()
        toolExec.shutdownNow()
        if (!keepError) JarvisState.set(JState.IDLE)
    }

    private fun fail(msg: String) {
        JarvisState.log("⚠ $msg")
        JarvisState.set(JState.ERROR, msg)
        stop(true)
        onEnd?.invoke()
    }

    // ───────────────────────── Bağlantı ─────────────────────────

    private fun connect() {
        if (!running) return
        ready = false
        val key = Prefs.apiKey(ctx)
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(setupJson().toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handle(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handle(bytes.utf8())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dropped(webSocket, "Bağlantı kapandı ($code) $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            dropped(webSocket, "Bağlantı hatası: ${t.message ?: ""} ${response?.code ?: ""}")
        }
    }

    private fun dropped(w: WebSocket, why: String) {
        if (w !== ws) return
        ready = false
        ws = null
        if (!running) return
        if (retries < 3) {
            retries++
            JarvisState.set(JState.CONNECTING, "Yeniden bağlanıyor…")
            main.postDelayed({ connect() }, 1500)
        } else {
            fail(why)
        }
    }

    private fun setupJson(): JSONObject {
        val voice = JSONObject().put(
            "voiceConfig",
            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.voice(ctx)))
        )
        val setup = JSONObject()
        setup.put("model", Prefs.model(ctx))
        setup.put(
            "generationConfig",
            JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", voice)
        )
        setup.put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Prompt.build(ctx))))
        )
        setup.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools.declarations())))
        setup.put("inputAudioTranscription", JSONObject())
        setup.put("outputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup)
    }

    // ───────────────────────── Gelen mesajlar ─────────────────────────

    private fun handle(text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        if (o.has("setupComplete")) {
            ready = true
            retries = 0
            JarvisState.set(JState.LISTENING, "Dinliyorum")
            return
        }
        val sc = o.optJSONObject("serverContent")
        if (sc != null) onServerContent(sc)
        val tc = o.optJSONObject("toolCall")
        if (tc != null) onToolCall(tc)
        if (o.has("goAway")) JarvisState.log("ℹ Oturum yenileniyor")
    }

    private fun onServerContent(sc: JSONObject) {
        if (sc.optBoolean("interrupted")) {
            clearPlayback()
            flushTranscripts()
            JarvisState.set(JState.LISTENING, "Dinliyorum")
        }
        sc.optJSONObject("inputTranscription")?.let { userBuf.append(it.optString("text")) }
        sc.optJSONObject("outputTranscription")?.let { aiBuf.append(it.optString("text")) }

        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                if (!data.isNullOrEmpty()) {
                    playQueue.offer(Base64.decode(data, Base64.DEFAULT))
                    if (JarvisState.state != JState.SPEAKING) JarvisState.set(JState.SPEAKING, "Konuşuyor")
                }
            }
        }
        if (sc.optBoolean("turnComplete")) {
            flushTranscripts()
            if (JarvisState.state != JState.SPEAKING) JarvisState.set(JState.LISTENING, "Dinliyorum")
        }
    }

    private fun flushTranscripts() {
        val u = userBuf.toString().trim()
        val a = aiBuf.toString().trim()
        userBuf.setLength(0)
        aiBuf.setLength(0)
        if (u.isNotEmpty()) JarvisState.log("Sen: $u")
        if (a.isNotEmpty()) JarvisState.log("JARVIS: $a")
    }

    private fun onToolCall(tc: JSONObject) {
        val calls = tc.optJSONArray("functionCalls") ?: return
        JarvisState.set(JState.THINKING, "İşlem yapıyor…")
        val socket = ws
        try {
            toolExec.execute {
                val responses = JSONArray()
                for (i in 0 until calls.length()) {
                    val fc = calls.optJSONObject(i) ?: continue
                    val name = fc.optString("name")
                    val id = fc.optString("id")
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    val result = try { tools.run(name, args) } catch (e: Exception) { "Hata: ${e.message}" }
                    JarvisState.log("🔧 $name → ${result.take(90)}")
                    responses.put(
                        JSONObject().put("id", id).put("name", name)
                            .put("response", JSONObject().put("result", result))
                    )
                }
                val msg = JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses))
                (ws ?: socket)?.send(msg.toString())
            }
        } catch (e: RejectedExecutionException) {
            // oturum kapanmış
        }
    }

    private fun clearPlayback() {
        playQueue.clear()
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (e: Exception) {}
    }

    // ───────────────────────── Ses ─────────────────────────

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val inRate = 16000
        val minIn = AudioRecord.getMinBufferSize(inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            inRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minIn, 8192)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            fail("Mikrofon açılamadı (izin verildi mi?)")
            return
        }
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true }
        }
        rec = r
        r.startRecording()
        micThread = Thread({ micLoop(r) }, "jarvis-mic").also { it.start() }

        val outRate = 24000
        val minOut = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minOut, 48000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        playThread = Thread({ playLoop(t) }, "jarvis-play").also { it.start() }
    }

    private fun micLoop(r: AudioRecord) {
        val buf = ByteArray(1280)
        while (running) {
            val n = try { r.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
            if (n <= 0) {
                try { Thread.sleep(20) } catch (e: InterruptedException) { break }
                continue
            }
            if (JarvisState.state == JState.LISTENING) JarvisState.level = rms(buf, n)
            val s = ws
            if (ready && s != null) {
                val audio = JSONObject()
                    .put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                    .put("mimeType", "audio/pcm;rate=16000")
                s.send(JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString())
            }
        }
    }

    private fun playLoop(t: AudioTrack) {
        while (running) {
            val chunk = try { playQueue.poll(300, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { break }
            if (chunk == null) {
                if (JarvisState.state == JState.SPEAKING) JarvisState.set(JState.LISTENING, "Dinliyorum")
                continue
            }
            JarvisState.level = rms(chunk, chunk.size)
            try { t.write(chunk, 0, chunk.size) } catch (e: Exception) {}
        }
    }

    private fun rms(b: ByteArray, n: Int): Float {
        var sum = 0.0
        var cnt = 0
        var i = 0
        while (i + 1 < n) {
            val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            sum += v.toDouble() * v.toDouble()
            cnt++
            i += 2
        }
        if (cnt == 0) return 0f
        return minOf(1f, (sqrt(sum / cnt) / 5000.0).toFloat())
    }
}
