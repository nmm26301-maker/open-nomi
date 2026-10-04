package ai.opennomi.app.voice

object VoiceSessionPolicy {
    fun canHandleRecognition(active: Boolean, submitted: Boolean, finishing: Boolean, speaking: Boolean, routing: Boolean) = active && !submitted && !finishing && !speaking && !routing
    fun playbackTimeout(audioPackets: Int): Long = if(audioPackets==0)8000L else 120000L
    fun staleRecovery(scheduledTurn: Int, currentTurn: Int, backgroundRequested: Boolean) = scheduledTurn!=currentTurn || !backgroundRequested
    fun keepListening(backgroundRequested: Boolean, continuous: Boolean) = backgroundRequested || continuous
    fun fallbackReply(modelReply: String, screenReply: String): String = modelReply.trim().ifBlank { screenReply.trim() }
        .ifBlank { "这次没有收到小智的回答，请检查网络和小智后台语音合成设置。" }
}
