package ai.opennomi.app

import ai.opennomi.app.screen.*
import org.junit.Assert.*
import org.junit.Test

class AudioCaptionRegressionTest {
    private fun caption(text:String)=AudioCaption(text,"source $text",.9,"英语")
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
    @Test fun cancellingDisplayDiscardsOldTextAndItsDeadline() {
        val p=AudioCaptionPresenter();p.offer(caption("old"),0);p.offer(caption("waiting"),100);p.reset()
        assertEquals("new",p.offer(caption("new"),101)!!.translated);assertNull(p.tick(1600))
    }
    @Test fun longCaptionsHaveABoundedDwellAndBlankTranslationsAreRejected() {
        val p=AudioCaptionPresenter();p.offer(caption("字".repeat(200)),0);p.offer(caption("next"),100)
        assertNull(p.tick(1499));assertEquals("next",p.tick(1500)!!.translated)
        try{p.offer(caption(""),2000);fail("Blank translated text must not be accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun recentVideoTextSurvivesPauseButIsClearedWhenSharingEnds() {
        ScreenState.begin();ScreenState.update{it.copy(audio=true,caption="actual",captionHistory=listOf(caption("actual")))}
        ScreenState.update{it.copy(audio=false)};assertEquals(1,ScreenState.state.value.captionHistory.size)
        ScreenState.end();assertTrue(ScreenState.state.value.captionHistory.isEmpty());assertEquals("",ScreenState.state.value.caption);ScreenState.begin();assertTrue(ScreenState.state.value.captionHistory.isEmpty());ScreenState.end()
    }
}
