package ai.opennomi.app

import ai.opennomi.app.screen.*
import org.junit.Assert.*
import org.junit.Test

class AudioCaptionRegressionTest {
    private fun caption(text:String)=AudioCaption(text,"source $text",.9,"英语")
    @Test fun pausingVideoKeepsTheShareAndHistoryWithoutFallingBackToOcrTranslation() {
        val id=ScreenState.begin();val recent=caption("已经完成")
        ScreenState.update{it.copy(audio=true,translation=true,caption="当前",captionOriginal="current",audioPreview="partial",audioQueued=4,captionQueued=2,captionHistory=listOf(recent))}
        ScreenState.pauseVideo();val state=ScreenState.state.value
        assertTrue(state.active);assertEquals(id,state.session);assertFalse(state.audio);assertFalse(state.translation)
        assertEquals(listOf(recent),state.captionHistory);assertEquals("",state.caption);assertEquals("",state.captionOriginal);assertEquals("",state.audioPreview);assertEquals(0,state.audioQueued);assertEquals(0,state.captionQueued)
        ScreenState.end()
    }
    @Test fun aCaptureFailurePreservesItsExplanationAndCompletedHistoryWhileDisablingTranslation() {
        ScreenState.begin();val recent=caption("保留");ScreenState.update{it.copy(audio=true,translation=true,captionHistory=listOf(recent))}
        ScreenState.pauseVideo(notice="播放应用禁止捕获",status="请重新开启字幕")
        assertFalse(ScreenState.state.value.translation);assertEquals("播放应用禁止捕获",ScreenState.state.value.notice);assertEquals("请重新开启字幕",ScreenState.state.value.status);assertEquals(listOf(recent),ScreenState.state.value.captionHistory)
        ScreenState.end()
    }
    @Test fun firstSubtitleAppearsImmediately() {
        val p=AudioCaptionPresenter();val first=caption("你好");assertEquals(first,p.offer(first,100));assertEquals(0,p.size)
    }
    @Test fun aBurstKeepsEverySubtitleAndRepeatedSpeechInDisplayOrder() {
        val p=AudioCaptionPresenter();val first=caption("不");val second=caption("不");val third=caption("是")
        assertEquals(first,p.offer(first,0));assertNull(p.offer(second,100));assertNull(p.offer(third,200))
        assertNull(p.tick(749));assertEquals(second,p.tick(750));assertNull(p.tick(1499));assertEquals(third,p.tick(1500));assertNull(p.tick(2250))
    }
    @Test fun aFullDisplayQueueAppliesBackpressureInsteadOfReplacingUnreadText() {
        val p=AudioCaptionPresenter();p.offer(caption("first"),0);p.offer(caption("second"),10);p.offer(caption("third"),20)
        assertFalse(p.canAccept)
        try{p.offer(caption("fourth"),30);fail("Full queue must not replace a waiting subtitle")}catch(_:IllegalStateException){}
        assertEquals("second",p.tick(750)!!.translated);assertTrue(p.canAccept);assertEquals("third",p.tick(1500)!!.translated)
    }
    @Test fun liveTranslationNeverWaitsForUnreadCaptionsAndTheLatestResultHasABoundedDelay() {
        val p=AudioCaptionPresenter(live=true)
        assertEquals("字".repeat(100),p.offer(caption("字".repeat(100)),0)!!.translated)
        repeat(50){index->
            assertTrue("Display pacing must not stall the translation worker",p.canAccept)
            assertNull(p.offer(caption("line $index"),100L+index*10))
            assertEquals("Only the latest live result waits for display",1,p.size)
        }
        assertNull(p.tick(1199));assertEquals("line 49",p.tick(1200)!!.translated)
        assertEquals(0,p.size);assertTrue(p.canAccept)
    }
    @Test fun liveResetCannotShowAResultFromThePreviousSession() {
        val p=AudioCaptionPresenter(live=true)
        p.offer(caption("old"),0);p.offer(caption("pending"),10);p.reset()
        assertEquals("new",p.offer(caption("new"),11)!!.translated)
        assertNull(p.tick(10000))
    }
    @Test fun cancellingDisplayDiscardsOldTextAndItsDeadline() {
        val p=AudioCaptionPresenter();p.offer(caption("old"),0);p.offer(caption("waiting"),100);p.reset()
        assertEquals("new",p.offer(caption("new"),101)!!.translated);assertNull(p.tick(1600))
    }
    @Test fun longCaptionsHaveABoundedDwellAndBlankTranslationsAreRejected() {
        val p=AudioCaptionPresenter();p.offer(caption("字".repeat(200)),0);p.offer(caption("next"),100)
        assertNull(p.tick(4499));assertEquals("next",p.tick(4500)!!.translated)
        try{p.offer(caption(""),5000);fail("Blank translated text must not be accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun readingTimeAccountsForChineseAndSupplementaryCharacters() {
        assertEquals(750L,AudioCaptionPresenter.readingTimeMs("你好"))
        assertEquals(2900L,AudioCaptionPresenter.readingTimeMs("中".repeat(20)))
        assertEquals(1100L,AudioCaptionPresenter.readingTimeMs("a".repeat(20)))
        assertEquals(4500L,AudioCaptionPresenter.readingTimeMs("中".repeat(200)))
        assertEquals(AudioCaptionPresenter.readingTimeMs("中".repeat(20)),AudioCaptionPresenter.readingTimeMs("𠀀".repeat(20)))
    }
    @Test fun recentVideoTextSurvivesPauseButIsClearedWhenSharingEnds() {
        ScreenState.begin();ScreenState.update{it.copy(audio=true,caption="actual",captionHistory=listOf(caption("actual")),audioQueued=3,captionQueued=2)}
        ScreenState.update{it.copy(audio=false)};assertEquals(1,ScreenState.state.value.captionHistory.size)
        ScreenState.end();assertTrue(ScreenState.state.value.captionHistory.isEmpty());assertEquals("",ScreenState.state.value.caption);assertEquals(0,ScreenState.state.value.audioQueued);assertEquals(0,ScreenState.state.value.captionQueued);ScreenState.begin();assertTrue(ScreenState.state.value.captionHistory.isEmpty());ScreenState.end()
    }
}
