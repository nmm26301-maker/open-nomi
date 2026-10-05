package ai.opennomi.app

import ai.opennomi.app.voice.*
import org.junit.Assert.*
import org.junit.Test

class NativeControlRegressionTest {
    @Test fun systemSilenceCanRestartWithoutIncreasingFailureBackoff() {
        repeat(100) { assertEquals(700L, NativeRecognitionPolicy.delay(6,0)); assertEquals(700L,NativeRecognitionPolicy.delay(7,0)) }
    }
    @Test fun providerFailuresBackOffAndStopAfterFiveRetries() {
        assertEquals(700L,NativeRecognitionPolicy.delay(2,1))
        assertEquals(3500L,NativeRecognitionPolicy.delay(2,5))
        assertNull(NativeRecognitionPolicy.delay(2,6))
        assertNull(NativeRecognitionPolicy.delay(9,1))
        assertNull(NativeRecognitionPolicy.delay(12,1))
        assertNull(NativeRecognitionPolicy.delay(13,1))
        assertNull(NativeRecognitionPolicy.delay(10,1))
    }
    @Test fun directControlDoesNotRequireCloudConversationOrAnAgentGoal() {
        assertEquals(VoiceCommand("open_app","微信"),NativeCommandRouter.route("打开微信")!!.commands.single())
        assertEquals(VoiceCommand("torch","off"),NativeCommandRouter.route("关掉手电筒")!!.commands.single())
        assertEquals(VoiceCommand("camera","capture"),NativeCommandRouter.route("拍照")!!.commands.single())
        assertEquals(listOf("camera","camera","camera"),NativeCommandRouter.route("打开相机，然后拍照，然后退出相机")!!.commands.map { it.action })
        assertEquals("打开微信搜索张三",NativeCommandRouter.route("帮我打开微信搜索张三")!!.goal)
    }
    @Test fun unsafeOrIncompleteChainsNeverFallThroughToAnAgent() {
        assertNull(NativeCommandRouter.route("打开微信，然后确认执行"))
        assertNull(NativeCommandRouter.route("打开微信，然后不要删除"))
        assertNull(NativeCommandRouter.route("不要打开相机"))
        assertNull(NativeCommandRouter.route("怎么打开相机"))
        assertNull(NativeCommandRouter.route("屏幕显示打开微信"))
    }
    @Test fun quotedInputIsDataAndConfirmationIsASeparateTurn() {
        assertEquals("你好，然后删除",NativeCommandRouter.route("输入“你好，然后删除”")!!.commands.single().target)
        assertEquals("confirm",NativeCommandRouter.route("确认执行")!!.commands.single().action)
        assertEquals("cancel",NativeCommandRouter.route("取消任务")!!.commands.single().action)
        assertEquals("stop",NativeCommandRouter.route("停止聆听")!!.commands.single().action)
    }
    @Test fun volumeLimitsAndIndependentCommands() {
        assertEquals(VoiceCommand("volume","30"),VoiceCommands.parse("把音量调到百分之30"))
        assertEquals(VoiceCommand("volume","mute"),VoiceCommands.parse("静音"))
        assertEquals(VoiceCommand("volume","up"),VoiceCommands.parse("音量大一点"))
        assertEquals(VoiceCommand("volume","unmute"),VoiceCommands.parse("取消静音"))
        assertNull(VoiceCommands.parse("音量调到101"))
    }
}
