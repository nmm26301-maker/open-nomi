package ai.opennomi.app.voice

import android.content.Context
import ai.opennomi.app.screen.*
import kotlinx.coroutines.*

/** Android adapter of the observe/plan/execute loop in Open-AutoGLM (Apache-2.0).
 * Uses fresh accessible controls in place of the upstream ADB executor.
 */
class HandsFreeTasks(private val context:Context) {
    private val actions=HandsFreeActions(context)
    private val queue=CommandQueue()
    private var goal=""
    private var agentSteps=0
    private var agentWaiting=false
    private var agentChoice:VoiceCommand?=null
    private val history=mutableListOf<String>()
    private var lastObservation=""
    private var repeated=0
    private var session=0L
    var paused=false;private set
    val hasTask get()=queue.hasTask || goal.isNotBlank()
    fun release() { clear();actions.release() }
    fun clear() {queue.clear();actions.clear();goal="";agentWaiting=false;agentChoice=null;paused=false;ScreenState.update{it.copy(agentRunning=false,busy=false,proposed=null,proposedPage=null,voiceTaskConfirmation=false)}}
    fun pause() {paused=true;ScreenState.update{it.copy(agentRunning=false,busy=false,status="任务已暂停，可说继续任务")}}
    suspend fun startAgent(task:String):String {
        clear();goal=task;agentSteps=0;history.clear();repeated=0;lastObservation="";session=ScreenState.state.value.session
        ScreenState.update{it.copy(task=task)}
        return runAgent()
    }
    suspend fun execute(commands:List<VoiceCommand>):String {
        val command=commands.singleOrNull()
        when(command?.action) {
            "cancel"->{clear();return "任务已取消，我继续听你说。"}
            "pause_task"->{pause();return "任务已暂停，请说继续任务或取消任务。"}
            "resume_task"->{paused=false;return if(goal.isNotBlank())runAgent() else if(queue.hasTask)runQueue() else "没有等待继续的任务。"}
        }
        if(agentWaiting && command?.action=="confirm") {
            val result=actions.executeResult(command);agentWaiting=false
            ScreenState.update{it.copy(proposed=null,proposedPage=null,voiceTaskConfirmation=false)}
            if(result.state!=ActionState.DONE){clear();return result.message}
            history.add(result.message);delay(600);return runAgent()
        }
        if(agentChoice!=null && command?.action=="open_app" && command.ordinal!=null && command.target==agentChoice?.target) {
            val result=actions.executeResult(command)
            if(result.state==ActionState.DONE){agentChoice=null;history.add(result.message);delay(600);return runAgent()}
            if(result.state==ActionState.FAILED)clear()
            return result.message
        }
        if(command!=null && queue.acceptsFollowup(command)) {
            val result=actions.executeResult(command);queue.result(result);ScreenState.event(result.message)
            if(result.state!=ActionState.DONE)return result.message
            delay(600);return runQueue(result.message)
        }
        clear();queue.start(commands);return runQueue()
    }
    private suspend fun runQueue(prefix:String=""):String {
        val replies=mutableListOf<String>();if(prefix.isNotBlank())replies.add(prefix)
        while(queue.hasTask && !paused) {
            currentCoroutineContext().ensureActive()
            val command=queue.current!!;val number=queue.index+1
            ScreenState.update{it.copy(agentRunning=true,status="正在执行第${number}/${queue.size}步")}
            if(command.action in setOf("click","like","type","scroll"))observe(false)
            val result=actions.executeResult(command)
            replies.add(result.message);ScreenState.event("第${number}步：${result.message}");queue.result(result)
            if(result.state!=ActionState.DONE)break
            if(queue.hasTask)delay(600)
        }
        ScreenState.update{it.copy(agentRunning=false)}
        return replies.joinToString(" ").ifBlank{"任务已结束。"}
    }
    private suspend fun observe(image:Boolean):Pair<Page,ScreenFrame?> {
        check(ScreenState.state.value.active){"请先开启屏幕共享"}
        val sharing=ScreenState.state.value.session
        var stable:Page?=null
        val pair=withTimeoutOrNull(7000) {
            while(isActive) {
                check(ScreenState.valid(sharing)){"屏幕共享已经停止"}
                val service=ScreenAccessService.instance ?: error("无障碍服务未连接")
                val page=service.readPage();val frame=ScreenState.frame
                check(!page.sensitive){"当前有密码框，任务已停止"}
                val matched=!image || (frame!=null && FrameReadiness.matches(sharing,page.app,page.version,frame.session,frame.pageApp,frame.pageVersion,frame.time,0,System.currentTimeMillis(),8000))
                if(!ScreenState.ownForeground && page.app.isNotBlank() && service.currentPackage()==page.app && stable?.app==page.app && stable?.version==page.version && matched)return@withTimeoutOrNull page to frame
                stable=page;delay(200)
            }
            null
        }
        return pair ?: error("页面仍在切换，请回到目标 App 后说继续任务")
    }
    private suspend fun runAgent():String {
        if(agentWaiting)return "当前步骤仍在等待确认，请单独说确认执行或取消任务。"
        agentChoice?.let{return "有多个${it.target}，请说打开第一个${it.target}，或指定第几个。"}
        val cfg=ScreenAssistant.settings()
        check(cfg.modelReady()){clear();"请先在连接页配置视觉模型，或直接说明确的操作指令"}
        check(ScreenState.valid(session)){clear();"屏幕共享已停止，请重新开启后再说任务"}
        while(goal.isNotBlank() && !paused) {
            currentCoroutineContext().ensureActive()
            if(agentSteps>=20){clear();return "已达到二十步上限，任务已停止。请查看任务记录。"}
            ScreenState.update{it.copy(agentRunning=true,busy=true,status="Agent 正在观察第${agentSteps+1}步")}
            val (page,frame)=observe(cfg.includeImage)
            val raw=withTimeout(45000){ScreenAssistant.planVoiceTask(goal,history.takeLast(12),page,frame)}
            currentCoroutineContext().ensureActive()
            check(ScreenState.valid(session)){"屏幕共享已停止"}
            val step=AgentPolicy.resolve(StepParser.parse(raw),page,context.resources.displayMetrics.widthPixels,context.resources.displayMetrics.heightPixels) ?: run{clear();return "模型没有给出可执行的动作，任务已停止。"}
            if(step.kind=="finish") {val answer=step.text.ifBlank{"模型判断任务结束，请核对页面结果。"};clear();return answer}
            val observation="${page.app}|${page.version}|$step"
            repeated=if(observation==lastObservation)repeated+1 else 0;lastObservation=observation
            if(repeated>=2){clear();return "连续操作后页面没有变化，任务已停止，避免重复点击。"}
            ScreenState.update{it.copy(busy=false)}
            val result=actions.executePlanned(step,page);agentSteps++;history.add("第${agentSteps}步 $step：${result.message}")
            ScreenState.event(history.last())
            when(result.state) {
                ActionState.CONFIRM->{agentWaiting=true;ScreenState.update{it.copy(agentRunning=false,proposed=step,proposedPage=page,voiceTaskConfirmation=true)};return result.message}
                ActionState.CHOICE->{agentChoice=VoiceCommand("open_app",step.text);ScreenState.update{it.copy(agentRunning=false,busy=false)};return result.message}
                ActionState.DONE->delay(700)
                else->{clear();return result.message}
            }
        }
        return "任务已暂停。"
    }
}

object AgentPolicy {
    fun resolve(step:Step?,page:Page,screenWidth:Int,screenHeight:Int):Step? {
        step ?: return null
        if(page.sensitive)return null
        return when(step.kind) {
            "tap" -> {
                if(step.x !in 0..999 || step.y !in 0..999 || screenWidth<=0 || screenHeight<=0)return null
                val x=step.x/1000f*screenWidth;val y=step.y/1000f*screenHeight
                // Bounds use real screen coordinates; callers scale the captured frame.
                page.nodes.filter{it.clickable && contains(it.bounds,x,y)}.minByOrNull{area(it.bounds)}?.let{Step("click",it.id)}
            }
            "click" -> step.takeIf{page.nodes.any{it.id==step.node && it.clickable}}
            "type" -> (if(step.node<0)page.nodes.filter{it.editable}.singleOrNull()?.let{step.copy(node=it.id)} else step)?.takeIf{candidate->page.nodes.any{it.id==candidate.node && it.editable} && candidate.text.length<=2000}
            "scroll" -> step.takeIf{it.text in setOf("","up","down")}
            "back","home","finish" -> step
            "open_app" -> step.takeIf{it.text.isNotBlank()}
            else->null
        }
    }
    private fun rect(s:String)=Regex("(-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+)").matchEntire(s)?.groupValues?.drop(1)?.map{it.toInt()}
    private fun contains(s:String,x:Float,y:Float)=rect(s)?.let{x>=it[0] && x<it[2] && y>=it[1] && y<it[3]} ?: false
    private fun area(s:String)=rect(s)?.let{(it[2]-it[0]).toLong()*(it[3]-it[1])} ?: Long.MAX_VALUE
}
