package ai.opennomi.app
import ai.opennomi.app.audio.SpeechGate
import ai.opennomi.app.voice.ResponseAccumulator
import org.junit.Assert.*
import org.junit.Test
class OriginalConversationRegressionTest {
    @Test fun shortEndpointEndsOnceAndNeverEndsSilenceBeforeSpeech() {
        var clock = 1000L
        val gate = SpeechGate(700, 3) { clock }
        clock += 10000; assertFalse(gate.consumeEnd())
        gate.accept(.08f); clock += 699; assertFalse(gate.consumeEnd())
        clock++; assertTrue(gate.consumeEnd()); assertFalse(gate.consumeEnd())
        gate.accept(.08f); clock += 700; assertTrue(gate.consumeEnd())
    }
    @Test fun repeatedResponseEventsDoNotDuplicateDisplayedText() {
        val accumulator = ResponseAccumulator()
        assertEquals("你好", accumulator.accept("你好"))
        assertEquals("", accumulator.accept("你好"))
        assertEquals("，我在这儿。", accumulator.accept("你好，我在这儿。"))
        accumulator.reset(); assertEquals("你好", accumulator.accept("你好"))
    }
}
