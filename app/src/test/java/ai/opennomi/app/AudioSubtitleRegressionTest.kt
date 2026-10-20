package ai.opennomi.app

import ai.opennomi.app.screen.AudioSubtitleQueue
import ai.opennomi.app.screen.SpeechConfidence
import org.junit.Assert.*
import org.junit.Test

class AudioSubtitleRegressionTest {


    @Test fun uncertainWordsAreNotHiddenByConfidentWordsAndInvalidScoresStayUnknown() {
        assertEquals(.2,SpeechConfidence.lowest(listOf(.99,.2,.98))!!,.001)
        assertNull(SpeechConfidence.lowest(listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,2.0,.6)))
        assertNull(SpeechConfidence.lowest(emptyList()));assertNull(SpeechConfidence.lowest(listOf(Double.NaN)))
    }
    @Test fun anUnknownUtteranceDoesNotBecomeConfidentWhenBatchedWithOtherSpeech() {
        val q=AudioSubtitleQueue();q.offer("first",.9);q.offer("unknown");q.offer("third",.95);q.offer("next",.9)
        assertNull(q.nextBatch(0)!!.confidence)
    }
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

    @Test fun confidenceIsRetainedForTheSpecificCompletedUtterance() {
        val q=AudioSubtitleQueue();q.offer("uncertain",.4);q.offer("clear",.9)
        assertEquals(.4,q.next(0)!!.confidence!!,0.001);q.success(q.next(0)!!.id)
        assertEquals(.9,q.next(1)!!.confidence!!,0.001)
    }
    @Test fun backlogBatchesOnlyAdjacentSpeechWithoutLosingOrReorderingIt() {
        val q=AudioSubtitleQueue();listOf("first","second","third","fourth").forEach{q.offer(it)}
        val batch=q.nextBatch(0)!!;assertEquals("first\nsecond\nthird",batch.text)
        q.success(batch);assertEquals("fourth",q.nextBatch(1)!!.text);assertEquals(1,q.size)
    }
    @Test fun normalSpeechNeverWaitsToFillABatch() {
        val q=AudioSubtitleQueue();q.offer("first");q.offer("second")
        assertEquals("first",q.nextBatch(0)!!.text)
    }
    @Test fun failedBatchRetriesExactlyItsOriginalTextDespiteNewSpeech() {
        val q=AudioSubtitleQueue();listOf("first","second","third","fourth").forEach{q.offer(it)}
        val batch=q.nextBatch(0)!!;q.failed(10);q.offer("fifth")
        assertNull(q.nextBatch(1009));assertEquals(batch,q.nextBatch(1010))
        q.success(batch);assertEquals("fourth",q.nextBatch(1011)!!.text)
    }
    @Test fun repeatedWordsAndLowConfidenceAreRetainedInABatch() {
        val q=AudioSubtitleQueue();q.offer("no",.9);q.offer("no",.4);q.offer("yes",.8);q.offer("next",.9)
        val batch=q.nextBatch(0)!!;assertEquals("no\nno\nyes",batch.text);assertEquals(.4,batch.confidence!!,.001)
    }
    @Test fun staleBatchCannotRemoveNewSpeechOrClearItsRetryDelay() {
        val q=AudioSubtitleQueue();q.offer("old");val old=q.nextBatch(0)!!
        q.reset();q.offer("new");val next=q.nextBatch(1)!!;q.failed(10);q.success(old)
        assertNull(q.nextBatch(1009));assertEquals(next,q.nextBatch(1010))
    }
    @Test fun aLongPhraseDoesNotPullInAnotherPhraseBeyondTheBatchLimit() {
        val q=AudioSubtitleQueue();q.offer("long ".repeat(80));repeat(3){q.offer("next")}
        assertEquals(1,q.nextBatch(0)!!.utterances.size);assertEquals(4,q.size)
    }

}
