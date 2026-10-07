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
    suspend fun drain(first:ControlRequest, continueAfter:()->Boolean = { true }, execute:suspend (ControlRequest)->String):String {
        val results=mutableListOf<String>();var request:ControlRequest?=first
        while(request!=null) {
            currentCoroutineContext().ensureActive()
            results.add(execute(request))
            currentCoroutineContext().ensureActive()
            // A task prompt belongs to the current task. Later utterances must not
            // replace it or accidentally confirm it before the prompt was shown.
            if(!continueAfter()) { clear(); break }
            request=next()
        }
        return results.joinToString(" ")
    }
}

/** Shared by the native and cloud lanes; independent of Android for regressions. */
data class TaskContinuation(val hasTask:Boolean=false, val paused:Boolean=false,
    val waiting:ActionState?=null, val current:VoiceCommand?=null) {
    val canDrain get()=!paused && waiting==null
    fun waitingMessage():String = when(waiting) {
        ActionState.CONFIRM -> "当前步骤等待确认，请单独说确认执行，或取消任务。"
        ActionState.CHOICE -> "当前步骤等待选择，请指定第几个${current?.target.orEmpty()}，或取消任务。"
        else -> "任务已暂停，可说继续任务或取消任务。"
    }
    fun rejection(request:ControlRequest,busy:Boolean=false):String? {
        val command=request.commands.singleOrNull()
        if(command?.action in setOf("cancel","pause_task","stop"))return null
        if(busy)return if(command?.action in setOf("confirm","resume_task"))
            "当前步骤还在执行，请等到提示后再说确认或继续。" else null
        if(paused && hasTask && command?.action!="resume_task")return "任务已暂停，请先说继续任务或取消任务。"
        if(waiting!=null && command?.action!="resume_task" &&
            (command==null || !accepts(waiting,current,command)))return waitingMessage()
        if(waiting==null && command?.action=="confirm")return "目前没有等待确认的操作。"
        return null
    }
    companion object {
        fun accepts(waiting:ActionState?,current:VoiceCommand?,command:VoiceCommand)=when(waiting) {
            ActionState.CONFIRM -> command.action=="confirm"
            ActionState.CHOICE -> command.action==current?.action &&
                (command.ordinal!=null || command.target.toIntOrNull()!=null) &&
                (command.action=="like" || command.target.equals(current?.target,true))
            else -> false
        }
    }
}
