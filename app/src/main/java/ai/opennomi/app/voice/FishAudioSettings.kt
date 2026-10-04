package ai.opennomi.app.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class FishAudioSettings(context: Context) {
    private val prefs = context.getSharedPreferences("open_nomi_fish_audio", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()
    var referenceId: String
        get() = prefs.getString("voice", "").orEmpty()
        set(value) = prefs.edit().putString("voice", value.trim()).apply()
    var model: String
        get() = prefs.getString("model", "s2.1-pro-free").orEmpty()
        set(value) = prefs.edit().putString("model", value.trim()).apply()
    var base: String
        get() = prefs.getString("base", "https://api.fish.audio").orEmpty()
        set(value) = prefs.edit().putString("base", value.trim().trimEnd('/')).apply()
    var speed: Double
        get() = prefs.getFloat("speed", 1f).toDouble()
        set(value) = prefs.edit().putFloat("speed", value.toFloat()).apply()
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("nomi-fish-audio", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nomi-fish-audio", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    var apiKey: String
        get() = runCatching {
            val bytes = Base64.decode(prefs.getString("secret", "").orEmpty(), Base64.NO_WRAP)
            if (bytes.isEmpty()) "" else Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            }
        }.getOrDefault("")
        set(value) {
            if (value.isBlank()) prefs.edit().remove("secret").apply()
            else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
                prefs.edit().putString("secret", Base64.encodeToString(cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).apply()
            }
        }
    fun configured() = prefs.contains("secret")
    fun connection() = FishAudioConfig(apiKey, referenceId, model, base, speed)
    /** Validate and encrypt before publishing the non-secret preferences. */
    fun save(config: FishAudioConfig, useFish: Boolean) {
        if (useFish || config.apiKey.isNotBlank()) config.validate()
        apiKey = config.apiKey
        referenceId = config.referenceId; model = config.model; base = config.base; speed = config.speed
        enabled = useFish
    }
}
