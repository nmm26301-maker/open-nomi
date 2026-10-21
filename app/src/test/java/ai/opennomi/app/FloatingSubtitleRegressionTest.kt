package ai.opennomi.app

import ai.opennomi.app.screen.*
import org.junit.Assert.*
import org.junit.Test

class FloatingSubtitleRegressionTest {
    private val recent=AudioCaption("确实已经完成的译文","actual original",.9,"英语")
    @Test fun backgroundViewUsesTheSameCompletedTranslationBeforeAnyLongErrorNotice() {
        val state=WorkspaceState(active=true,audio=true,translation=true,caption="已经翻译的内容",captionOriginal="original",notice="网络提示".repeat(200))
        val rows=FloatingSubtitle.sections(state,true,true,true)
        assertEquals("已经翻译的内容",rows[1].text);assertTrue(rows[1].translated)
        assertEquals("原文：original",rows[2].text)
        assertEquals("由 Google 翻译",rows.last().text)
        assertTrue(rows.first{it.text.startsWith("网络提示")}.text.length<=240)
    }
    @Test fun partialSpeechIsLabelledAsRecognitionInsteadOfInventingATranslation() {
        val rows=FloatingSubtitle.sections(WorkspaceState(active=true,audio=true,translation=true,audioPreview="not final"),true,true,true)
        assertTrue(rows.any{it.text=="识别中：not final"});assertTrue(rows.none{it.translated})
        assertFalse(rows.any{it.text=="由 Google 翻译"})
    }
    @Test fun idleCaptureStillHasAVisibleWaitingMessage() {
        val rows=FloatingSubtitle.sections(WorkspaceState(active=true,audio=true,translation=true),true,true,true)
        assertTrue(rows.any{it.text.contains("等待视频原声")})
    }
    @Test fun draftIsExplicitlyProvisionalAndDoesNotReplaceConfirmedHistory() {
        val state=WorkspaceState(active=true,audio=true,translation=true,caption=recent.translated,
            captionHistory=listOf(recent),captionDraft="不要开门",captionDraftOriginal="do not open the door")
        val rows=FloatingSubtitle.sections(state,true,true,true)
        assertTrue(rows[1].text.contains("临时译文") && rows[1].text.contains("可能改写"))
        assertEquals("不要开门",rows[2].text);assertTrue(rows[2].translated)
        assertTrue(rows.any{it.text==recent.translated})
        assertEquals(listOf(recent),state.captionHistory)
        assertFalse(FloatingSubtitle.sections(state.copy(translation=false),true,true,true).any{it.text.contains("不要开门")})
    }
    @Test fun hidingTheViewDoesNotMutateOrDiscardCapturedHistory() {
        val state=WorkspaceState(active=true,audio=true,translation=true,caption="current",captionHistory=listOf(recent))
        assertTrue(FloatingSubtitle.sections(state,false,true,true).isEmpty())
        assertTrue(state.audio);assertEquals(listOf(recent),state.captionHistory)
        assertTrue(FloatingSubtitle.sections(state,true,true,true).any{it.text=="current"})
    }
    @Test fun pausedViewExplicitlyLabelsItsLastCompletedCaptionAndPreservesAnError() {
        val state=WorkspaceState(active=true,captionHistory=listOf(recent),notice="捕获中断")
        val rows=FloatingSubtitle.sections(state,true,true,true)
        assertTrue(rows.first().text.contains("已暂停"));assertEquals("上一句：${recent.translated}",rows[1].text)
        assertTrue(rows.any{it.text=="捕获中断"})
    }
    @Test fun stoppingSharingRemovesAllFloatingTranscriptEvenIfAStaleStateHasText() {
        assertTrue(FloatingSubtitle.sections(WorkspaceState(caption="old",notice="old",captionHistory=listOf(recent)),true,true,true).isEmpty())
    }
    @Test fun displayOptionsCannotChangeTheCapturedTranslationOrMislabelAnOnlineProvider() {
        val rows=FloatingSubtitle.sections(WorkspaceState(active=true,audio=true,translation=true,caption="译文",captionOriginal="source"),true,false,false)
        assertTrue(rows.any{it.text=="译文"});assertFalse(rows.any{it.text.startsWith("原文：") || it.text=="由 Google 翻译"})
        assertEquals(SubtitlePosition.BOTTOM,SubtitlePosition.selected("unknown"))
        assertTrue(FloatingSubtitle.WINDOW_ALPHA<=.8f)
    }
}
