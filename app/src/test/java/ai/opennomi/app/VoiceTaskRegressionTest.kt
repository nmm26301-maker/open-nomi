package ai.opennomi.app

import ai.opennomi.app.voice.*
import ai.opennomi.app.screen.*
import ai.opennomi.app.network.XiaozhiAudioWire
import org.junit.Assert.*
import org.junit.Test

class VoiceTaskRegressionTest {
    @Test fun missingTaskControlRecognitionRestartsOnlyItsOwnCapture() {
        assertTrue(VoiceSessionPolicy.restartTaskCapture(2,2,true,true,true,false))
        assertFalse(VoiceSessionPolicy.restartTaskCapture(2,3,true,true,true,false))
        assertFalse(VoiceSessionPolicy.restartTaskCapture(2,2,false,true,true,false))
        assertFalse(VoiceSessionPolicy.restartTaskCapture(2,2,true,false,true,false))
        assertFalse(VoiceSessionPolicy.restartTaskCapture(2,2,true,true,false,false))
        assertFalse(VoiceSessionPolicy.restartTaskCapture(2,2,true,true,true,true))
    }
    @Test fun openThenExitAndNextUtteranceBothParse() {
        assertEquals(listOf(VoiceCommand("open_app","微信"),VoiceCommand("exit_app")),VoiceTasks.parse("打开微信，然后退出应用")!!.commands)
        assertEquals(VoiceCommand("exit_app"),VoiceCommands.parse("退出应用"))
        assertEquals(VoiceCommand("exit_app"),VoiceCommands.parse("小智，关闭当前应用。"))
        assertNull(VoiceCommands.parse("不要退出应用"))
    }
    @Test fun quotedDataNeverExecutesAsACommandAndUnknownStepsAbortWholeChain() {
        assertEquals(listOf(VoiceCommand("type","你好然后退出应用"),VoiceCommand("click","搜索")),VoiceTasks.parse("输入“你好然后退出应用”，然后点击搜索")!!.commands)
        assertNull(VoiceTasks.parse("输入你好然后退出应用"))
        assertTrue(VoiceTasks.parse("打开微信然后给陌生人发消息")!!.commands.isEmpty())
        assertTrue(VoiceTasks.parse("打开微信然后给陌生人发消息")!!.error.isNotBlank())
        assertTrue(VoiceTasks.parse("点击发送然后确认执行")!!.commands.isEmpty())
    }
    @Test fun chainKeepsRemainderUntilChoiceAndConfirmationActuallySucceed() {
        val q=CommandQueue();val click=VoiceCommand("click","发送");val exit=VoiceCommand("exit_app")
        q.start(listOf(click,exit));q.result(ActionResult("等待确认",ActionState.CONFIRM))
        assertEquals(click,q.current);assertEquals(0,q.index)
        assertFalse(q.acceptsFollowup(VoiceCommand("click","其它",1)))
        assertTrue(q.acceptsFollowup(VoiceCommand("confirm")))
        q.result(ActionResult("执行成功",ActionState.DONE));assertEquals(exit,q.current)
        q.result(ActionResult("执行成功",ActionState.DONE));assertFalse(q.hasTask)
    }
    @Test fun failedOperationNeverAdvancesOrRunsRemainingCommands() {
        val q=CommandQueue();q.start(listOf(VoiceCommand("open_app","未知"),VoiceCommand("like")))
        q.result(ActionResult("找不到",ActionState.FAILED));assertFalse(q.hasTask)
        q.start(listOf(VoiceCommand("click","搜索"),VoiceCommand("exit_app")))
        q.result(ActionResult("多个按钮",ActionState.CHOICE));assertTrue(q.acceptsFollowup(VoiceCommand("click","搜索",2)))
        assertFalse(q.acceptsFollowup(VoiceCommand("confirm")))
    }
    @Test fun modelActsOnlyOnExistingControlsAndScalesToPhysicalScreen() {
        val page=Page("test",nodes=listOf(ScreenNode(7,"播放",false,true,"100 200 300 400"),ScreenNode(8,"输入",true,false,"0 0 100 100")))
        assertEquals(Step("click",7),AgentPolicy.resolve(Step("tap",x=200,y=300),page,1000,1000))
        assertNull(AgentPolicy.resolve(Step("tap",x=900,y=900),page,1000,1000))
        assertNull(AgentPolicy.resolve(Step("click",99),page,1000,1000))
        assertEquals(Step("type",8,"你好"),AgentPolicy.resolve(Step("type",text="你好"),page,1000,1000))
        assertNull(AgentPolicy.resolve(Step("click",7),page.copy(sensitive=true),1000,1000))
        assertNull(AgentPolicy.resolve(Step("shell",text="ignored"),page,1000,1000))
    }
    @Test fun autoGlmLaunchTypeAndSwipeAreParsedAsData() {
        assertEquals(Step("open_app",text="微信"),StepParser.parse("do(action=\"Launch\", app=\"微信\")"))
        assertEquals(Step("type",text="你好"),StepParser.parse("do(action=\"Type\", text=\"你好\")"))
        assertEquals(Step("scroll",text="up"),StepParser.parse("do(action=\"Swipe\", direction=\"up\")"))
        assertEquals("打开微信并找到联系人",VoiceTasks.agentGoal("小智，执行任务：打开微信并找到联系人"))
    }
    @Test fun protocolVersionsRoundTripAndMalformedFramesNeverReachDecoder() {
        val opus=byteArrayOf(11,12,13)
        for(version in 1..3)assertArrayEquals(opus,XiaozhiAudioWire.decode(XiaozhiAudioWire.encode(opus,version),version))
        assertNull(XiaozhiAudioWire.decode(byteArrayOf(0,0,0,9,11),3))
        assertNull(XiaozhiAudioWire.decode(ByteArray(16),2))
        assertNull(XiaozhiAudioWire.decode(ByteArray(0),1))
    }
}
