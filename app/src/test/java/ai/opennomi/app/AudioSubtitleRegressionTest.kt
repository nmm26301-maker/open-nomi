package ai.opennomi.app

import ai.opennomi.app.screen.AudioSubtitleQueue
import ai.opennomi.app.screen.AudioPhraseClock
import org.junit.Assert.*
import org.junit.Test

class AudioSubtitleRegressionTest {
    @Test fun speechDuringTranslationKeepsEveryCompletedUtteranceInOrder() {
        val q=AudioSubtitleQueue();q.offer("first sentence");val first=q.next(0)!!
        q.offer("second sentence");q.offer("third sentence")
        q.success(first.id);assertEquals("second sentence",q.next(1)!!.text)
        q.success(q.next(1)!!.id);assertEquals("third sentence",q.next(2)!!.text)
        q.success(q.next(2)!!.id);assertNull(q.next(3))
    }
    @Test fun repeatedSpokenSentencesRemainSeparateUtterances() {
        val q=AudioSubtitleQueue();q.offer("no");q.offer("no")
        val first=q.next(0)!!;q.success(first.id);assertEquals("no",q.next(1)!!.text)
        assertNotEquals(first.id,q.next(1)!!.id)
    }
    @Test fun failureRetriesTheSameUtteranceBeforeNewSpeech() {
        val q=AudioSubtitleQueue();q.offer("keep this");q.failed(10);q.offer("then this")
        assertNull(q.next(1009));assertEquals("keep this",q.next(1010)!!.text)
        q.success(q.next(1010)!!.id);assertEquals("then this",q.next(1011)!!.text)
    }
    @Test fun overflowIsExplicitRatherThanSilentlyReplacingSpeech() {
        val q=AudioSubtitleQueue(2);assertTrue(q.offer("first"));assertTrue(q.offer("second"));assertFalse(q.offer("third"))
        assertEquals("first",q.next(0)!!.text);assertEquals(2,q.size)
    }
    @Test fun cancellationDiscardsPendingSpeechAndStaleCompletionsCannotRemoveNewSpeech() {
        val q=AudioSubtitleQueue();q.offer("old");val old=q.next(0)!!.id
        q.failed(0);q.reset();q.offer("new");q.success(old)
        assertEquals("new",q.next(0)!!.text)
    }
    @Test fun longMonologueHasABoundaryButNormalPausesResetItsClock() {
        val clock=AudioPhraseClock();assertFalse(clock.update(false,0));assertFalse(clock.update(true,0))
        assertFalse(clock.update(true,5499));assertTrue(clock.update(true,5500));clock.reset()
        assertFalse(clock.update(true,6000));assertFalse(clock.update(true,11000));assertTrue(clock.update(true,11500))
    }
    @Test fun confidenceIsRetainedForTheSpecificCompletedUtterance() {
        val q=AudioSubtitleQueue();q.offer("uncertain",.4);q.offer("clear",.9)
        assertEquals(.4,q.next(0)!!.confidence!!,0.001);q.success(q.next(0)!!.id)
        assertEquals(.9,q.next(1)!!.confidence!!,0.001)
    }
}
