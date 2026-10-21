package ai.opennomi.app

import ai.opennomi.app.screen.*
import org.junit.Assert.*
import org.junit.Test

class AudioDraftRegressionTest {
    @Test fun growingSpeechYieldsOnlyRepeatedCompleteWordsAndBoundsWork() {
        val gate=AudioDraftGate()
        gate.observe("one two three four");assertNull(gate.next(0))
        gate.observe("one two three four five")
        assertEquals("one two three four",gate.next(100)?.text)
        gate.observe("one two three four five six");assertNull(gate.next(1200))
        assertEquals("one two three four five",gate.next(1300)?.text)
        assertNull(gate.next(5000))
    }
    @Test fun revisedNegationRejectsAnInFlightOrDisplayedPreview() {
        val gate=AudioDraftGate()
        gate.observe("please open the door now");gate.observe("please open the door now slowly")
        val ticket=checkNotNull(gate.next(0));assertTrue(gate.accepts(ticket))
        gate.observe("please do not open the door")
        assertFalse(gate.accepts(ticket));assertNull(gate.next(1500))
    }
    @Test fun finalPauseAndResyncRejectOldResultsEvenWhenNextUtteranceIsIdentical() {
        val gate=AudioDraftGate()
        gate.observe("one two three four");gate.observe("one two three four five")
        val ticket=checkNotNull(gate.next(0))
        gate.reset()
        gate.observe("one two three four");gate.observe("one two three four five")
        assertFalse(gate.accepts(ticket));assertNotEquals(ticket,gate.next(1500))
    }
    @Test fun blankRecognitionClearsTheCandidateAndOversizedSpeechIsBoundedAtAWord() {
        val gate=AudioDraftGate()
        val phrase=(1..40).joinToString(" "){"word$it"}
        gate.observe(phrase);gate.observe("$phrase last")
        val ticket=checkNotNull(gate.next(0))
        assertTrue(ticket.text.length<=96);assertTrue(phrase.startsWith(ticket.text+" "))
        gate.observe("");assertFalse(gate.accepts(ticket));assertNull(gate.next(5000))
    }
}
