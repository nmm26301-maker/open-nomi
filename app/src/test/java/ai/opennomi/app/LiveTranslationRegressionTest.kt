package ai.opennomi.app

import ai.opennomi.app.screen.TranslationBuffer
import ai.opennomi.app.screen.TranslationText
import ai.opennomi.app.voice.VoiceReplyGate
import ai.opennomi.app.model.ConversationState
import org.junit.Assert.*
import org.junit.Test

class LiveTranslationRegressionTest {
    @Test fun latestSubtitleSurvivesInFlightRequestWithoutBuildingBacklog() {
        val b=TranslationBuffer();b.offer("one");assertEquals("one",b.next(0))
        b.offer("two");b.offer("three");b.success("one","一")
        assertEquals("three",b.next(1));b.success("three","三");assertNull(b.next(2))
    }
    @Test fun retryRetainsLatestSubtitleAndRecoversAfterDelay() {
        val b=TranslationBuffer();b.offer("one");b.failed(100)
        b.offer("new");assertNull(b.next(2099));assertEquals("new",b.next(2100))
        b.success("new","新");assertEquals("新",b.cached("new"));assertNull(b.next(3000))
    }
    @Test fun configResetDiscardsCacheAndRetryDelay() {
        val b=TranslationBuffer();b.offer("one");b.success("one","一");b.failed(100)
        b.reset();assertNull(b.cached("one"));assertNull(b.next(0));b.offer("one");assertEquals("one",b.next(0))
    }
    @Test fun mixedChinesePageKeepsForeignSubtitleInsteadOfSkippingEntirePage() {
        assertEquals("Welcome to my channel",TranslationText.foreignLines("推荐\nWelcome to my channel\n已点赞\n2026"))
        assertEquals("",TranslationText.foreignLines("这是中文页面\n12345"))
    }
    @Test fun ttsStartsEvenWithoutPrecedingSttButCannotWakePausedSession() {
        assertTrue(VoiceReplyGate.startsPlayback(true,ConversationState.LISTENING,"start"))
        assertTrue(VoiceReplyGate.startsPlayback(true,ConversationState.THINKING,"sentence_start"))
        assertFalse(VoiceReplyGate.startsPlayback(false,ConversationState.IDLE,"start"))
    }
    @Test fun voiceFramesDoNotEnterSilentTranslationOrFinishedTurn() {
        assertTrue(VoiceReplyGate.acceptsReply(true,false,false))
        assertFalse(VoiceReplyGate.acceptsReply(true,true,false))
        assertFalse(VoiceReplyGate.acceptsReply(true,false,true))
        assertFalse(VoiceReplyGate.acceptsReply(false,false,false))
    }
}
