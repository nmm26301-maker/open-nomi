package ai.opennomi.app.screen

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.util.concurrent.TimeUnit

data class VisionConnection(val base: String, val model: String, val key: String)

/** Protocol code shared by screen questions, imported pictures and the connection test. */
class VisionApi(private val client: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(90, TimeUnit.SECONDS).connectTimeout(12, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    companion object {
        const val FREE_BASE = "https://open.bigmodel.cn/api/paas/v4"
        const val FREE_MODEL = "glm-4.6v-flash"
        const val KEY_PAGE = "https://bigmodel.cn/usercenter/proj-mgmt/apikeys"
        const val MODEL_DOC = "https://docs.bigmodel.cn/cn/guide/models/free/glm-4.6v-flash"
        fun endpoint(base: String): HttpUrl {
            val url = base.trim().trimEnd('/').toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "地址不能包含账号、密钥或查询参数" }
            val host = url.host
            val segments = host.split('.')
            val octets = segments.mapNotNull { it.toIntOrNull() }
            val privateIp = segments.size == 4 && octets.size == 4 && octets.all { it in 0..255 } &&
                (octets[0] == 127 || octets[0] == 10 ||
                    (octets[0] == 192 && octets[1] == 168) ||
                    (octets[0] == 172 && octets[1] in 16..31))
            val local = host in setOf("localhost", "::1") || privateIp
            require(url.scheme == "https" || local) { "请使用 HTTPS 或本地模型地址" }
            val path = url.encodedPath.trimEnd('/')
            val full = when {
                path.endsWith("/chat/completions") -> path
                path.isEmpty() -> "/v1/chat/completions"
                else -> "$path/chat/completions"
            }
            return url.newBuilder().encodedPath(full).build()
        }
        fun body(cfg: VisionConnection, messages: JSONArray, maxTokens: Int = 1000): JSONObject {
            require(cfg.model.isNotBlank()) { "请填写模型名称" }
            return JSONObject().put("model", cfg.model.trim()).put("messages", messages)
                .put("stream", false).put("temperature", 0.1).put("max_tokens", maxTokens).apply {
                    if (endpoint(cfg.base).host == "open.bigmodel.cn" && cfg.model.startsWith("glm-4.6v"))
                        put("thinking", JSONObject().put("type", "disabled"))
                }
        }
        fun imageUrl(cfg: VisionConnection, jpegBase64: String): String =
            if (endpoint(cfg.base).host == "open.bigmodel.cn") jpegBase64 else "data:image/jpeg;base64,$jpegBase64"
        fun content(result: JSONObject): String {
            val choice = result.optJSONArray("choices")?.optJSONObject(0) ?: error("服务没有返回回答，请检查是否使用对话接口")
            val message = choice.optJSONObject("message") ?: error("回答格式不兼容")
            val value = message.opt("content")
            val text = when (value) {
                is String -> value
                is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it)?.optString("text") }.joinToString("\n")
                else -> ""
            }
            require(text.isNotBlank()) { if (choice.optString("finish_reason") == "length") "模型生成达到长度限制；请关闭思考或增大输出长度" else "服务返回了空回答，尚不能确认视觉可用" }
            return text.trim()
        }
        fun failure(code: Int) = when (code) {
            401 -> "密钥无效或已过期，请重新生成并粘贴"
            403 -> "账号无权使用此模型，请在官方平台开通权限"
            404 -> "模型名或接口地址不存在，请检查服务地区与模型名称"
            429 -> "服务限流或额度不足，请稍后重试并检查账号额度"
            in 300..399 -> "接口发生跳转，请填写服务的最终 HTTPS 地址"
            in 500..599 -> "模型服务暂时不可用，请稍后重试"
            else -> "服务返回 $code，请检查模型是否接受图片与请求参数"
        }
    }
    suspend fun complete(cfg: VisionConnection, messages: JSONArray, maxTokens: Int = 1000): String {
        val url = endpoint(cfg.base)
        require(url.host != "open.bigmodel.cn" || cfg.key.isNotBlank()) { "请先在官方平台创建 API Key，并在这里粘贴" }
        val builder = Request.Builder().url(url).post(body(cfg, messages, maxTokens).toString().toRequestBody("application/json".toMediaType()))
        if (cfg.key.isNotBlank()) builder.header("Authorization", "Bearer ${cfg.key.trim()}")
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(IOException("无法连接模型服务，请检查手机网络或服务地址", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            check(it.isSuccessful) { failure(it.code) }
                            // Bound the read before allocating the entire body.
                            val source = it.body?.source() ?: error("服务没有返回内容")
                            val buffer = okio.Buffer()
                            while (source.read(buffer, 8192) != -1L) check(buffer.size <= 2_000_000) { "服务响应过大" }
                            val bytes = buffer.readByteArray()
                            val result = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
                                .getOrElse { error("服务未返回有效 JSON，请检查是否填写了网页地址") }
                            val text = content(result)
                            if (cont.isActive) cont.resume(text)
                        } catch (t: Throwable) { if (cont.isActive) cont.resumeWithException(t) }
                    }
                }
            })
        }
    }
}
