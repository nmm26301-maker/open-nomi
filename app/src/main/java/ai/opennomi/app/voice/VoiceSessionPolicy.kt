package ai.opennomi.app.voice

object VoiceSessionPolicy {
    fun keepListening(backgroundRequested: Boolean, continuous: Boolean) = backgroundRequested || continuous
    fun fallbackReply(modelReply: String, screenReply: String): String = modelReply.trim().ifBlank { screenReply.trim() }
        .ifBlank { "这次没有收到小智的回答，请检查网络和小智后台语音合成设置。" }
}
