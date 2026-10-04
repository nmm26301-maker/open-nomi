package ai.opennomi.app.voice

enum class ActionState { DONE, FAILED, CHOICE, CONFIRM }
data class ActionResult(val message: String, val state: ActionState = ActionState.FAILED)
data class VoiceSequence(val commands: List<VoiceCommand> = emptyList(), val error: String = "")

/** Explicit user-authored chains only. Quoted input is kept as data. */
object VoiceTasks {
    private val separator = Regex("(?:[，,。;；]\\s*)?(?:然后|接着|之后|再(?=打开|关闭|退出|点击|输入|返回|回到|向|点))\\s*|[，,;；]\\s*(?=打开|启动|关闭|退出|点击|输入|返回|回到|向|点)")
    fun parse(raw: String): VoiceSequence? {
        val chunks=mutableListOf<String>();var start=0;var quote:Char?=null;var i=0
        while(i<raw.length) {
            val ch=raw[i]
            if(quote!=null) { if(ch==quote)quote=null;i++;continue }
            if(ch=='“' || ch=='"') {quote=if(ch=='“')'”' else '"';i++;continue}
            // An unquoted input payload can contain the word 然后. Never split it.
            if(raw.substring(start,i).trim().let{it.startsWith("输入") && !it.removePrefix("输入").trimStart().startsWith("“") && !it.removePrefix("输入").trimStart().startsWith("\"")}) {i++;continue}
            val match=separator.find(raw,i)?.takeIf{it.range.first==i}
            if(match!=null) {chunks.add(raw.substring(start,i).trim());i=match.range.last+1;start=i} else i++
        }
        if(chunks.isEmpty())return null
        if(quote!=null)return VoiceSequence(error="输入文字的引号没有闭合，任务没有执行。")
        chunks.add(raw.substring(start).trim())
        if(chunks.size>12)return VoiceSequence(error="一次最多十二步，请分成两条任务。")
        val commands=chunks.mapIndexed { n,part -> VoiceCommands.parse(part) ?: return VoiceSequence(error="第${n+1}步还不支持：$part。整个任务没有执行；可说执行任务加上你的目标。") }
        if(commands.any{it.action in setOf("stop","confirm","cancel","pause_task","resume_task")})return VoiceSequence(error="确认、暂停和取消请单独说，任务没有执行。")
        return VoiceSequence(commands)
    }
    fun agentGoal(raw: String): String? = Regex("^(?:小智[，, ]*)?(?:请)?(?:帮我完成|执行任务[：:，, ]*|开始任务[：:，, ]*)(.+)$").find(raw.trim())?.groupValues?.get(1)?.trim()?.takeIf{it.isNotBlank()}
}

/** A waiting step is advanced only after real execution, never after a prompt. */
class CommandQueue {
    private var steps=emptyList<VoiceCommand>()
    var index=0;private set
    var waiting:ActionState?=null;private set
    val current get()=steps.getOrNull(index)
    val size get()=steps.size
    val hasTask get()=current!=null
    fun start(commands:List<VoiceCommand>) {steps=commands;index=0;waiting=null}
    fun clear(){steps=emptyList();index=0;waiting=null}
    fun result(result:ActionResult) {
        when(result.state) {ActionState.DONE->{index++;waiting=null};ActionState.FAILED->clear();else->waiting=result.state}
    }
    fun acceptsFollowup(command:VoiceCommand)=when(waiting) {
        ActionState.CONFIRM->command.action=="confirm"
        ActionState.CHOICE->command.action==current?.action && (command.ordinal!=null || command.target.toIntOrNull()!=null) && (command.action=="like" || command.target.equals(current?.target,true))
        else->false
    }
}
