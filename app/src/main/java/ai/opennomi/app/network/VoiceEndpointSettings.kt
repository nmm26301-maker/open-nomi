package ai.opennomi.app.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class VoiceEndpoint(val url:String, val token:String="", val version:Int=1) {
    fun validate() {
        val uri=runCatching {URI(url.trim())}.getOrNull()
        require(uri!=null && uri.scheme in setOf("ws","wss") && !uri.host.isNullOrBlank() &&
            uri.userInfo==null && uri.fragment==null && uri.query==null) {"请填写 WebSocket 地址；密钥填在 Token 一栏"}
        val host=uri!!.host.lowercase()
        val privateHost=host in setOf("localhost","127.0.0.1","[::1]","::1") || host.endsWith(".local") ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            (host.startsWith("172.") && (host.split('.').getOrNull(1)?.toIntOrNull() ?: -1) in 16..31)
        require(uri.scheme=="wss" || privateHost) {"公网语音接口请使用 wss://；局域网可用 ws://"}
        require(version in 1..3) {"小智协议版本必须是 1、2 或 3"}
        require(!token.contains('\n') && !token.contains('\r')) {"Token 格式错误"}
    }
}

/** One alternate profile, with its token protected by Android Keystore. */
class VoiceEndpointSettings(context:Context) {
    private val prefs=context.getSharedPreferences("open_nomi_voice_endpoint",Context.MODE_PRIVATE)
    var enabled:Boolean
        get()=prefs.getBoolean("enabled",false)
        set(value)=prefs.edit().putBoolean("enabled",value).apply()
    val url:String get()=prefs.getString("url","").orEmpty()
    val version:Int get()=prefs.getInt("version",1)
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        return (store.getKey("nomi-voice-endpoint",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nomi-voice-endpoint",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    val token:String get()=runCatching {
        val bytes=Base64.decode(prefs.getString("secret","").orEmpty(),Base64.NO_WRAP)
        if(bytes.isEmpty()) "" else Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            String(doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
        }
    }.getOrDefault("")
    fun connection()=VoiceEndpoint(url,token,version)
    fun save(config:VoiceEndpoint,useAlternate:Boolean) {
        if(useAlternate || config.url.isNotBlank())config.validate()
        val secret=if(config.token.isBlank()) "" else Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE,key())
            Base64.encodeToString(iv+doFinal(config.token.trim().toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
        }
        prefs.edit().putString("url",config.url.trim()).putString("secret",secret)
            .putInt("version",config.version).putBoolean("enabled",useAlternate).apply()
    }
}
