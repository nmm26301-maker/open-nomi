package ai.opennomi.app

import ai.opennomi.app.voice.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ControlLaneRegressionTest {
    @Test fun lateChatTextTtsAndAudioCannotInterruptTheIndependentControlLane() {
        assertFalse(VoiceSessionPolicy.allowCloudReply(true,false))
        assertTrue(VoiceSessionPolicy.allowCloudReply(true,true))
        assertTrue(VoiceSessionPolicy.allowCloudReply(false,false))
    }
    @Test fun separateTorchUtterancesUseOneObserverAndFinishWithoutAPlaybackDependency() = runBlocking {
        val requests=mutableListOf<Boolean>()
        lateinit var hardware:AcknowledgedSwitch
        hardware=AcknowledgedSwitch { enabled -> requests.add(enabled);launch { delay(20);hardware.observed(enabled) } }
        hardware.observed(false)
        val lane=ControlInbox()
        suspend fun turn(text:String):String = lane.drain(ControlRequest(listOf(VoiceCommands.parse(text)!!))) {
            hardware.set(it.commands.single().target=="on",500)
            "done"
        }
        assertEquals("done",withTimeout(1000){turn("打开手电筒")})
        assertEquals("done",withTimeout(1000){turn("关掉手电筒")})
        assertEquals("done",withTimeout(1000){turn("关掉手电筒")})
        assertEquals(listOf(true,false),requests)
    }
    @Test fun lateInitialCallbackCannotAcknowledgeAnOppositeRequestedState() = runBlocking {
        lateinit var hardware:AcknowledgedSwitch
        hardware=AcknowledgedSwitch { launch { hardware.observed(false);delay(70);hardware.observed(true) } }
        val request=async { hardware.set(true,500) }
        delay(30);assertFalse(request.isCompleted)
        request.await()
    }
    @Test fun unavailableTorchNeverReportsSuccessAndCanRecoverOnTheNextTurn() = runBlocking {
        lateinit var hardware:AcknowledgedSwitch
        var working=false
        hardware=AcknowledgedSwitch { target -> if(working)hardware.observed(target) else hardware.unavailable() }
        try { hardware.set(true,60);fail("unavailable torch must time out") } catch(expected:TimeoutCancellationException) {}
        working=true;hardware.set(true,300);hardware.set(false,300)
    }
    @Test fun aCameraFollowupWaitsForSavingAndIsNotLostDuringExecution() = runBlocking {
        val lane=ControlInbox();val saved=CompletableDeferred<Unit>();val order=mutableListOf<String>()
        val task=async {
            lane.drain(ControlRequest(listOf(VoiceCommand("camera","capture")))) { request ->
                val action=request.commands.single().target
                if(action=="capture")saved.await()
                order.add(action);action
            }
        }
        yield()
        assertTrue(lane.offer(ControlRequest(listOf(VoiceCommands.parse("退出相机")!!))))
        assertFalse(task.isCompleted)
        saved.complete(Unit)
        assertEquals("capture close",task.await())
        assertEquals(listOf("capture","close"),order)
    }
    @Test fun clearRemovesUnexecutedFollowupsAndCapacityIsBounded() {
        val lane=ControlInbox()
        repeat(12){assertTrue(lane.offer(ControlRequest(listOf(VoiceCommand("torch","on")))))}
        assertFalse(lane.offer(ControlRequest(listOf(VoiceCommand("torch","off")))))
        lane.clear();assertNull(lane.next())
    }
    @Test fun userCameraAndTorchChainsAreRealCommandsAndQuestionsStayChat() {
        assertEquals(listOf(VoiceCommand("torch","on"),VoiceCommand("torch","off")),VoiceTasks.parse("打开手电筒，然后关掉手电筒")!!.commands)
        assertEquals(listOf("open","capture","close"),VoiceTasks.parse("打开相机，然后拍照，然后退出相机")!!.commands.map{it.target})
        assertEquals(listOf("open","capture"),VoiceTasks.parse("打开相机拍照")!!.commands.map{it.target})
        assertEquals(VoiceCommand("type","你好"),VoiceCommands.parse("打字你好"))
        assertEquals("打开微信搜索张三",PhoneIntent.goal("帮我打开微信搜索张三"))
        listOf("不要打开相机","怎么拍照","屏幕显示打开微信","你能不能打开相机","天气怎么样").forEach { assertNull(it,PhoneIntent.goal(it)) }
    }
}
