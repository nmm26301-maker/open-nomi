package ai.opennomi.app.voice
import android.content.Context
/** Original preferences are retained across cover installs. */
class VoiceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("open_nomi_voice", Context.MODE_PRIVATE)
    var continuousConversation: Boolean
        get() = prefs.getBoolean("continuous", true)
        set(value) = prefs.edit().putBoolean("continuous", value).apply()
    var realtimeConversation: Boolean
        get() = prefs.getBoolean("offline_duplex", true)
        set(value) = prefs.edit().putBoolean("offline_duplex", value).apply()
    var systemSpeechFallback: Boolean
        get() = prefs.getBoolean("system_speech_fallback", false)
        set(value) = prefs.edit().putBoolean("system_speech_fallback", value).apply()
    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduce_motion", false)
        set(value) = prefs.edit().putBoolean("reduce_motion", value).apply()
}

