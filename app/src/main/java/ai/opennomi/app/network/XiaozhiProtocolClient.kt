package ai.opennomi.app.network

import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

class XiaozhiProtocolClient(
    private val url: String,
    private val token: String,
    private val deviceId: String,
    private val boardUuid: String,
    private val listener: Listener,
    private val aecAvailable: Boolean = false,
) {
    interface Listener {
        fun onOpen()
        fun onClosed(error: Throwable? = null)
        fun onStt(text: String)
        fun onResponseText(text: String)
        fun onTtsState(state: String)
        fun onEmotion(emotion: String)
        fun onAudio(opus: ByteArray)
        fun onAudioFormat(sampleRate: Int) {}
    }

    private companion object {
        // Reconnects share the HTTP dispatcher instead of creating another thread pool.
        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
    private var socket: WebSocket? = null
    @Volatile private var sessionId: String = ""
    @Volatile private var ready = false
    @Volatile private var closed = false
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var handshake: ScheduledFuture<*>? = null
    private fun fail(error: Throwable?) {
        synchronized(this) { if (closed) return; closed = true; ready = false }
        handshake?.cancel(false); timer.shutdownNow(); socket?.cancel()
        listener.onClosed(error)
    }

    fun connect() {
        val request = Request.Builder()
            .url(url)
            .header("Protocol-Version", "3")
            .header("Device-Id", deviceId)
            .header("Client-Id", boardUuid)
            .header("User-Agent", "ESP32-S3-BOX-3/2.2.4")
            .apply { if (token.isNotBlank()) header("Authorization", if (token.startsWith("Bearer ", true)) token else "Bearer $token") }
            .build()
        socket = http.newWebSocket(request, wsListener)
    }

    fun disconnect() {
        synchronized(this) {
            closed = true; ready = false; handshake?.cancel(false); timer.shutdownNow()
            if (socket?.close(1000, "bye") != true) socket?.cancel()
            socket = null
        }
    }

    fun sendListen(state: String = "start", mode: String = "manual") {
        if (ready) sendJson(JSONObject().put("session_id", sessionId).put("type", "listen").put("state", state).put("mode", mode))
    }

    fun sendAbort() {
        if (ready) sendJson(JSONObject().put("session_id", sessionId).put("type", "abort"))
    }

    fun sendText(text: String) {
        sendJson(JSONObject().put("session_id", sessionId).put("type", "listen").put("state", "detect").put("text", text))
    }

    fun sendAudio(opus: ByteArray) {
        if (!ready) return
        val buffer = ByteBuffer.allocate(4 + opus.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0)
        buffer.put(0)
        buffer.putShort(opus.size.toShort())
        buffer.put(opus)
        if (socket?.send(ByteString.of(*buffer.array())) != true) fail(IllegalStateException("网络发送失败"))
    }

    private fun sendHello() {
        val hello = JSONObject()
            .put("type", "hello")
            .put("version", 3)
            .put("transport", "websocket")
            .put("features", JSONObject().put("aec", aecAvailable))
            .put("audio_params", JSONObject()
                .put("format", "opus")
                .put("sample_rate", 16000)
                .put("channels", 1)
                .put("frame_duration", 60))
        sendJson(hello)
    }

    private fun sendJson(json: JSONObject) { if (socket?.send(json.toString()) != true) fail(IllegalStateException("网络发送失败")) }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@XiaozhiProtocolClient) {
                if (closed) { webSocket.cancel(); return }
                socket = webSocket
                handshake = timer.schedule({ if (!ready) fail(IllegalStateException("语音协议握手超时")) }, 15, TimeUnit.SECONDS)
                sendHello()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed) return
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (json.optString("type")) {
                "hello" -> {
                    sessionId = json.optString("session_id", "")
                    val params = json.optJSONObject("audio_params")
                    if (sessionId.isBlank() || (params != null && (params.optInt("sample_rate", 16000) !in setOf(8000, 12000, 16000, 24000, 48000) || params.optInt("channels", 1) != 1 || params.optString("format", "opus") != "opus"))) {
                        fail(IllegalStateException("服务端语音格式不兼容")); return
                    }
                    if (!ready) { listener.onAudioFormat(params?.optInt("sample_rate", 16000) ?: 16000); ready = true; handshake?.cancel(false); listener.onOpen() }
                }
                "error" -> fail(IllegalStateException(json.optString("message", "语音服务出错")))
                "stt" -> json.optString("text").takeIf { it.isNotBlank() }?.let(listener::onStt)
                "llm" -> json.optString("emotion").takeIf { it.isNotBlank() }?.let(listener::onEmotion)
                "tts" -> {
                    json.optString("state").takeIf { it.isNotBlank() }?.let(listener::onTtsState)
                    if (json.optString("state") in setOf("sentence_start", "")) json.optString("text").takeIf { it.isNotBlank() }?.let(listener::onResponseText)
                    json.optString("emotion").takeIf { it.isNotBlank() }?.let(listener::onEmotion)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!ready || closed) return
            val data = bytes.toByteArray()
            if (data.size >= 4 && data[0].toInt() == 0) {
                val size = ((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255)
                if (size > 0 && size == data.size - 4) listener.onAudio(data.copyOfRange(4, data.size))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail(t)
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(null)
    }
}
