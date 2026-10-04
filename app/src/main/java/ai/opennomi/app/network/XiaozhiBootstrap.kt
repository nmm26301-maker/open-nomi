package ai.opennomi.app.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class XiaozhiBootstrap(private val context: Context) {
    data class Identity(val deviceId: String, val boardUuid: String)
    data class Result(val websocketUrl: String, val token: String, val activationCode: String?, val protocolVersion:Int=1)

    private val prefs = context.getSharedPreferences("open_nomi_identity", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()

    fun identity(): Identity {
        val old = context.getSharedPreferences("open_nomi", Context.MODE_PRIVATE)
        var device = prefs.getString("device_id", null) ?: old.getString("device", null)
        var board = prefs.getString("board_uuid", null) ?: old.getString("client", null)
        if (device.isNullOrBlank()) {
            val bytes = ByteArray(6) { (0..255).random().toByte() }
            device = bytes.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
            prefs.edit().putString("device_id", device).apply()
        }
        if (board.isNullOrBlank()) {
            board = UUID.randomUUID().toString().lowercase()
            prefs.edit().putString("board_uuid", board).apply()
        }
        prefs.edit().putString("device_id", device).putString("board_uuid", board).apply()
        return Identity(device, board)
    }

    suspend fun bootstrap(): Result = withContext(Dispatchers.IO) {
        val id = identity()
        val old = context.getSharedPreferences("open_nomi", Context.MODE_PRIVATE)
        val endpoint = old.getString("url", "").orEmpty().trim()
        if (endpoint.isNotBlank() && endpoint != "wss://api.tenclass.net/xiaozhi/v1/") {
            require(endpoint.startsWith("wss://") || endpoint.startsWith("ws://")) { "保存的语音服务地址无效" }
            return@withContext Result(endpoint, old.getString("token", "").orEmpty(), null, old.getInt("protocol_version",1).takeIf{it in 1..3} ?: 1)
        }
        val body = JSONObject().apply {
            put("version", 2)
            put("mac_address", id.deviceId)
            put("uuid", id.boardUuid)
            put("chip_model_name", "esp32s3")
            put("application", JSONObject().put("name", "xiaozhi").put("version", "2.2.4"))
            put("ota", JSONObject().put("label", "ota_0"))
            put("board", JSONObject().put("type", "box-3"))
        }
        val req = Request.Builder()
            .url("https://api.tenclass.net/xiaozhi/ota/")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Device-Id", id.deviceId)
            .header("Client-Id", id.boardUuid)
            .header("Activation-Version", "1")
            .header("User-Agent", "ESP32-S3-BOX-3/2.2.4")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("设备登记失败: HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val ws = json.getJSONObject("websocket")
            Result(
                websocketUrl = ws.getString("url"),
                token = ws.optString("token", ""),
                activationCode = json.optJSONObject("activation")?.optString("code")?.takeIf { it.isNotBlank() },
                protocolVersion = ws.optInt("version",1).also{require(it in 1..3){"服务端指定了不支持的语音协议"}}
            )
        }
    }
}

