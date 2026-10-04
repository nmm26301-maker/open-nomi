package ai.opennomi.app.voice

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fish's model is an HTTP header; reference_id selects the voice, not the model. */
data class FishAudioConfig(
    val apiKey: String,
    val referenceId: String = "",
    val model: String = "s2.1-pro-free",
    val base: String = "https://api.fish.audio",
    val speed: Double = 1.0,
) {
    fun validate() {
        require(apiKey.isNotBlank()) { "请先填写 FishAudio API Key" }
        require(!apiKey.contains('\n') && !apiKey.contains('\r')) { "FishAudio 密钥格式错误" }
        require(model.matches(Regex("[A-Za-z0-9._-]+"))) { "请填写有效的 FishAudio 模型名称" }
        require(speed in 0.5..2.0) { "FishAudio 语速范围为 0.5 到 2" }
        FishAudioApi.endpoint(base)
    }
}

class FishAudioApi(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()) {
    companion object {
        const val MAX_AUDIO_BYTES = 16 * 1024 * 1024
        fun endpoint(base: String): HttpUrl {
            val url = base.trim().trimEnd('/').toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "FishAudio 地址不能包含密码或参数" }
            require(url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1")) { "FishAudio 服务地址需要 HTTPS" }
            val path = url.encodedPath.trimEnd('/')
            val target = when {
                path.endsWith("/v1/tts") -> path
                path.endsWith("/v1") -> "$path/tts"
                else -> "$path/v1/tts"
            }
            return url.newBuilder().encodedPath(target).build()
        }
        /** Split at punctuation where possible, without cutting a UTF-16 surrogate pair. */
        fun speechParts(text: String): List<String> {
            val remaining = text.trim()
            require(remaining.isNotBlank()) { "没有可朗读的文字" }
            require(remaining.length <= 12000) { "FishAudio 回复过长，文字已保留，请缩短回复后再朗读" }
            val parts = mutableListOf<String>()
            var start = 0
            while (start < remaining.length) {
                var end = minOf(start + 500, remaining.length)
                if (end < remaining.length) {
                    val split = (end - 1 downTo start + 250).firstOrNull { remaining[it] in "。！？；.!?;\n" }
                    if (split != null) end = split + 1
                    if (Character.isHighSurrogate(remaining[end - 1])) end--
                }
                parts += remaining.substring(start, end)
                start = end
            }
            return parts
        }
        private fun failure(code: Int) = IOException(when (code) {
            401, 403 -> "FishAudio 密钥无效或没有模型/音色权限，请检查配置"
            402 -> "FishAudio 账户额度不足，请检查账户"
            404 -> "FishAudio 服务地址或音色不存在，请检查配置"
            429 -> "FishAudio 请求过于频繁或额度受限，请稍后再试"
            400, 422 -> "FishAudio 未接受合成参数，请检查模型与音色 ID"
            else -> "FishAudio 服务暂不可用（HTTP $code）"
        })
    }
    suspend fun synthesize(config: FishAudioConfig, text: String): ByteArray {
        config.validate()
        require(text.isNotBlank() && text.length <= 500) { "FishAudio 单段文字长度无效" }
        val body = JSONObject().put("text", text).put("format", "mp3").put("latency", "balanced")
            .put("prosody", JSONObject().put("speed", config.speed).put("volume", 0))
        if (config.referenceId.isNotBlank()) body.put("reference_id", config.referenceId.trim())
        val request = Request.Builder().url(endpoint(config.base))
            .header("Authorization", "Bearer ${config.apiKey.trim()}").header("model", config.model)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("FishAudio 连接失败，请检查网络或服务地址"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use { result ->
                            if (!result.isSuccessful) throw failure(result.code)
                            val audio = result.body ?: throw IOException("FishAudio 没有返回音频")
                            val type = audio.contentType()?.toString().orEmpty()
                            if (type.contains("json") || type.contains("text/")) throw IOException("FishAudio 返回了文字而非 MP3 音频，请检查服务地址")
                            if (audio.contentLength() > MAX_AUDIO_BYTES) throw IOException("FishAudio 音频过大")
                            val output = ByteArrayOutputStream()
                            audio.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > MAX_AUDIO_BYTES) throw IOException("FishAudio 音频过大")
                                    output.write(buffer, 0, count)
                                }
                            }
                            output.toByteArray().also { if (it.isEmpty()) throw IOException("FishAudio 返回了空音频") }
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is IOException && e.message.orEmpty().startsWith("FishAudio")) e else IOException("FishAudio 音频接收失败，请检查网络"))
                    }
                }
            })
        }
    }
}
