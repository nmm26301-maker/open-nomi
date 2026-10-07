package ai.opennomi.app

import ai.opennomi.app.voice.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TaskContinuationRegressionTest {
    @Test fun laterQueuedUtterancesCannotOverwriteAWaitingStep() = runBlocking {
        val inbox=ControlInbox();val queue=CommandQueue()
        val send=VoiceCommand("click","发送");val exit=VoiceCommand("exit_app")
        queue.start(listOf(send,exit))
        inbox.offer(ControlRequest(listOf(VoiceCommand("torch","on"))))
        inbox.offer(ControlRequest(listOf(VoiceCommand("confirm"))))
        val executed=mutableListOf<ControlRequest>()
        val first=ControlRequest(listOf(send,exit))
        inbox.drain(first,{TaskContinuation(queue.hasTask,waiting=queue.waiting,current=queue.current).canDrain}) {
            executed.add(it);queue.result(ActionResult("等待确认",ActionState.CONFIRM));"等待确认"
        }
        assertEquals(listOf(first),executed)
        assertEquals(send,queue.current);assertEquals(0,queue.index);assertNull(inbox.next())
        assertTrue(queue.acceptsFollowup(VoiceCommand("confirm")))
        queue.result(ActionResult("已发送",ActionState.DONE));assertEquals(exit,queue.current)
    }
    @Test fun waitingSelectionPreservesTheOriginalAppAndButton() {
        val app=TaskContinuation(true,waiting=ActionState.CHOICE,current=VoiceCommand("open_app","微信"))
        assertNull(app.rejection(ControlRequest(listOf(VoiceCommand("open_app","微信",2)))))
        assertNotNull(app.rejection(ControlRequest(listOf(VoiceCommand("open_app","抖音",2)))))
        assertNotNull(app.rejection(ControlRequest(goal="去抖音找视频")))
        val button=app.copy(current=VoiceCommand("click","搜索"))
        assertNull(button.rejection(ControlRequest(listOf(VoiceCommand("click","搜索",1)))))
        assertNotNull(button.rejection(ControlRequest(listOf(VoiceCommand("click","发送",1)))))
    }
    @Test fun confirmationMustArriveAfterTheTaskPromptAndCannotBeQueuedEarly() {
        val confirm=ControlRequest(listOf(VoiceCommand("confirm")))
        assertNotNull(TaskContinuation().rejection(confirm,true))
        assertNotNull(TaskContinuation().rejection(confirm))
        assertNull(TaskContinuation(true,waiting=ActionState.CONFIRM).rejection(confirm))
        assertNotNull(TaskContinuation(true,waiting=ActionState.CONFIRM).rejection(ControlRequest(listOf(VoiceCommand("torch","on")))))
    }
    @Test fun pauseAndPageTimeoutKeepTheUnexecutedRemainder() {
        val queue=CommandQueue();queue.start(listOf(VoiceCommand("open_app","微信"),VoiceCommand("click","搜索"),VoiceCommand("type","张三")))
        queue.result(ActionResult("已打开",ActionState.DONE))
        val paused=TaskContinuation(queue.hasTask,true,current=queue.current)
        assertFalse(paused.canDrain)
        assertNotNull(paused.rejection(ControlRequest(listOf(VoiceCommand("camera","capture")))))
        assertNull(paused.rejection(ControlRequest(listOf(VoiceCommand("resume_task")))))
        assertEquals(1,queue.index);assertEquals("搜索",queue.current!!.target)
        queue.result(ActionResult("已点击",ActionState.DONE));assertEquals("张三",queue.current!!.target)
    }
    @Test fun cancelAndStopRemainAvailableInEveryWaitingState() {
        for(waiting in listOf(null,ActionState.CONFIRM,ActionState.CHOICE)) {
            val task=TaskContinuation(true,true,waiting,VoiceCommand("click","发送"))
            for(action in listOf("cancel","pause_task","stop")) {
                val request=ControlRequest(listOf(VoiceCommand(action)))
                assertNull(task.rejection(request));assertNull(task.rejection(request,true))
            }
            assertNull(task.rejection(ControlRequest(listOf(VoiceCommand("resume_task")))))
        }
    }
    @Test fun dictatedWordsIncludingThenAreAlwaysInputData() {
        for(prefix in listOf("输入","打字","请帮我输入","小智，请打字")) {
            val request=NativeCommandRouter.route(prefix+"你好，然后退出应用")!!
            assertEquals(listOf(VoiceCommand("type","你好，然后退出应用")),request.commands)
        }
        val task=NativeCommandRouter.route("打开微信，然后请帮我打字你好，然后退出应用")!!
        assertEquals(listOf(VoiceCommand("open_app","微信"),VoiceCommand("type","你好，然后退出应用")),task.commands)
    }
    @Test fun quotedInputCanBeFollowedByAnExplicitStepAndBrokenQuotesAbort() {
        assertEquals(listOf(VoiceCommand("type","你好，然后返回"),VoiceCommand("click","搜索")),
            NativeCommandRouter.route("请帮我打字“你好，然后返回”，然后点击搜索")!!.commands)
        assertNull(NativeCommandRouter.route("打字“你好，然后退出应用"))
        assertNull(NativeCommandRouter.route("打开微信，然后打字“你好"))
    }
}
