package ai.opennomi.app.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ControlRequest(val commands:List<VoiceCommand> = emptyList(), val goal:String?=null)
/** Spoken follow-ups are serialized while a previous phone action is still running. */
class ControlInbox {
    private val waiting=ArrayDeque<ControlRequest>()
    fun offer(request:ControlRequest):Boolean {
        if(waiting.size>=12)return false
        waiting.addLast(request);return true
    }
    fun next():ControlRequest?=if(waiting.isEmpty())null else waiting.removeFirst()
    fun clear()=waiting.clear()
    suspend fun drain(first:ControlRequest, execute:suspend (ControlRequest)->String):String {
        val results=mutableListOf<String>();var request:ControlRequest?=first
        while(request!=null) {
            currentCoroutineContext().ensureActive()
            results.add(execute(request))
            currentCoroutineContext().ensureActive()
            request=next()
        }
        return results.joinToString(" ")
    }
}
