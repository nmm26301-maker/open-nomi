package ai.opennomi.app.voice

import ai.opennomi.app.model.ConversationState

/** TTS start is authoritative even if an STT message was omitted by the server. */
object VoiceReplyGate {
    fun startsPlayback(active: Boolean, state: ConversationState, event: String): Boolean =
        active && state != ConversationState.IDLE && event in setOf("start", "sentence_start")
    fun acceptsReply(active: Boolean, silent: Boolean, finishing: Boolean) = active && !silent && !finishing
    fun acceptsAudio(active: Boolean, silent: Boolean, state: ConversationState): Boolean =
        active && !silent && state == ConversationState.SPEAKING
}
