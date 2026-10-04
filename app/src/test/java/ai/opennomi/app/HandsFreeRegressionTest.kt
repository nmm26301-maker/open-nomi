package ai.opennomi.app

import ai.opennomi.app.voice.VoiceCommand
import ai.opennomi.app.voice.VoiceCommands
import ai.opennomi.app.voice.VoiceSessionPolicy
import org.junit.Assert.*
import org.junit.Test

class HandsFreeRegressionTest {
    @Test fun backgroundConversationSurvivesReplyEvenWhenSingleTurnPreferenceWasSaved() {
        assertTrue(VoiceSessionPolicy.keepListening(true,false))
        assertTrue(VoiceSessionPolicy.keepListening(true,true))
        assertTrue(VoiceSessionPolicy.keepListening(false,true))
        assertFalse(VoiceSessionPolicy.keepListening(false,false))
    }
    @Test fun textOnlyAndVisionRepliesHaveARealFallbackContent() {
        assertEquals("小智回答",VoiceSessionPolicy.fallbackReply(" 小智回答 ","视觉结果"))
        assertEquals("视觉结果",VoiceSessionPolicy.fallbackReply("","视觉结果"))
        assertFalse(VoiceSessionPolicy.fallbackReply("", " ").isBlank())
    }
    @Test fun userCanOperateWithoutTouchingConfirmationOrButtons() {
        assertEquals(VoiceCommand("torch","on"),VoiceCommands.parse("小智，帮我打开手电筒。"))
        assertEquals(VoiceCommand("torch","off"),VoiceCommands.parse("关掉手电筒"))
        assertEquals(VoiceCommand("like"),VoiceCommands.parse("点个赞"))
        assertEquals(VoiceCommand("confirm"),VoiceCommands.parse("确认执行"))
        assertEquals(VoiceCommand("cancel"),VoiceCommands.parse("取消执行"))
    }
    @Test fun questionsAndNegationsNeverTurnIntoHardwareActions() {
        listOf("怎么打开手电筒", "别打开手电筒", "不要点赞", "我昨天说打开手电筒", "屏幕显示：打开手电筒", "你觉得这个应该点赞吗").forEach { assertNull(it,VoiceCommands.parse(it)) }
    }
    @Test fun multipleLikesCanBeSelectedUsingSpokenOrdinals() {
        assertEquals(VoiceCommand("like","1"),VoiceCommands.parse("点击第一个点赞按钮"))
        assertEquals(VoiceCommand("like","3"),VoiceCommands.parse("点第三个点赞"))
    }
    @Test fun alreadyLikedButtonsAreNotToggledOff() {
        assertTrue(VoiceCommands.likeLabel("点赞，28"))
        assertTrue(VoiceCommands.likeLabel("Like"))
        listOf("已点赞", "取消点赞", "Unlike", "Liked", "不像", "我不喜欢").forEach { assertFalse(it,VoiceCommands.likeLabel(it)) }
    }
    @Test fun riskyButtonsRequireAnExplicitVoiceConfirmation() {
        listOf("发送", "确认订单", "付款", "删除照片", "Publish", "Allow").forEach { assertTrue(it,VoiceCommands.sensitive(it)) }
        listOf("点赞", "播放", "下一页", "搜索").forEach { assertFalse(it,VoiceCommands.sensitive(it)) }
    }
    @Test fun inputTextPreservesSpacesAndDoesNotSubmitTheForm() {
        assertEquals(VoiceCommand("type","hello world"),VoiceCommands.parse("输入hello world"))
        assertEquals(VoiceCommand("click","发送"),VoiceCommands.parse("点击发送按钮"))
        assertEquals(VoiceCommand("scroll","up"),VoiceCommands.parse("向上滚动"))
    }
}
