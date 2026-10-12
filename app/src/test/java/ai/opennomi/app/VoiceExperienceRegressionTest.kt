package ai.opennomi.app

import ai.opennomi.app.audio.SpeechGate
import ai.opennomi.app.voice.VoiceSessionPolicy
import org.junit.Assert.*
import org.junit.Test

class VoiceExperienceRegressionTest {
    @Test fun emptyRecognitionCannotStartOrInterruptATurn() {
        for(pending in listOf(false,true)) {
            assertTrue(VoiceSessionPolicy.ignoreTranscript("", "上一句话",pending))
            assertTrue(VoiceSessionPolicy.ignoreTranscript(" \n\t", "上一句话",pending))
        }
    }
    @Test fun retransmittedRecognitionDoesNotRestartAnAnswer() {
        assertTrue(VoiceSessionPolicy.ignoreTranscript(" 你好 ","你好",true))
        assertFalse(VoiceSessionPolicy.ignoreTranscript("换个话题","你好",true))
    }
    @Test fun userCanRepeatTheSameQuestionInANewListeningTurn() {
        assertFalse(VoiceSessionPolicy.ignoreTranscript("再说一遍","再说一遍",false))
    }
    @Test fun oldEndpointPreferenceIsPreservedUntilUserSelectsAPause() {
        assertEquals(480L,VoiceSessionPolicy.endpointMillis(null,true))
        assertEquals(700L,VoiceSessionPolicy.endpointMillis(null,false))
        assertEquals(1200L,VoiceSessionPolicy.endpointMillis(1200,true))
        assertEquals(700L,VoiceSessionPolicy.endpointMillis(700,true))
        assertEquals(480L,VoiceSessionPolicy.endpointMillis(480,false))
        assertEquals(700L,VoiceSessionPolicy.endpointMillis(-1,false))
    }
    @Test fun relaxedEndpointKeepsAThinkingPauseInsideOneUtterance() {
        var clock=1000L
        val gate=SpeechGate(1200,3){clock}
        gate.accept(.08f)
        clock+=800;gate.accept(0f);assertFalse(gate.consumeEnd())
        clock+=100;gate.accept(.08f)
        clock+=1199;assertFalse(gate.consumeEnd())
        clock++;assertTrue(gate.consumeEnd());assertFalse(gate.consumeEnd())
    }
    @Test fun briefNoiseDoesNotTriggerBargeInButSustainedSpeechDoes() {
        var clock=1000L
        val gate=SpeechGate(700,3){clock}
        clock+=700;assertFalse(gate.accept(.08f))
        clock+=60;assertFalse(gate.accept(0f))
        repeat(2){clock+=60;assertFalse(gate.accept(.08f))}
        clock+=60;assertTrue(gate.accept(.08f))
        clock+=60;assertFalse(gate.accept(.08f))
    }
}
