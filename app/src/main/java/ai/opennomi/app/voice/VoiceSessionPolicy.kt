package ai.opennomi.app.voice

object VoiceSessionPolicy {
    /** Ignore empty events and retransmitted final STT while the same answer is pending.
     * A new listening turn (including barge-in) may legitimately repeat the same words. */
    fun ignoreTranscript(text:String,previous:String,answerPending:Boolean) =
        text.isBlank() || (answerPending && previous.isNotBlank() && text.trim()==previous.trim())
    fun endpointMillis(selected:Long?,fastResponse:Boolean):Long =
        selected?.takeIf { it in setOf(480L,700L,1200L) } ?: if(fastResponse)480L else 700L
    fun resumeChat(userRequested:Boolean,backgroundRequested:Boolean,continuous:Boolean) = backgroundRequested || (userRequested && continuous)
    fun rememberChat(completed:Boolean,hasModelText:Boolean,explicitScreen:Boolean,silent:Boolean,user:String) = completed && hasModelText && !explicitScreen && !silent && user.isNotBlank()
    fun allowCloudReply(phoneControl:Boolean, explicitTextRequest:Boolean)=!phoneControl || explicitTextRequest
    fun canHandleRecognition(active: Boolean, submitted: Boolean, finishing: Boolean, speaking: Boolean, routing: Boolean) = active && !submitted && !finishing && !speaking && !routing
    fun playbackTimeout(audioPackets: Int): Long = if(audioPackets==0)30000L else 120000L
    fun staleRecovery(scheduledTurn: Int, currentTurn: Int, backgroundRequested: Boolean) = scheduledTurn!=currentTurn || !backgroundRequested
    fun keepListening(backgroundRequested: Boolean, continuous: Boolean) = backgroundRequested || continuous
    fun restartTaskCapture(scheduledTurn:Int,currentTurn:Int,active:Boolean,routing:Boolean,controls:Boolean,recording:Boolean) = scheduledTurn==currentTurn && active && routing && controls && !recording
    fun fallbackReply(modelReply: String, screenReply: String): String = modelReply.trim().ifBlank { screenReply.trim() }
        .ifBlank { "这次没有收到小智的回答，请检查网络和小智后台语音合成设置。" }
}
