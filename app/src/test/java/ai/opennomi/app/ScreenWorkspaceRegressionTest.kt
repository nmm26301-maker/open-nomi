package ai.opennomi.app

import ai.opennomi.app.screen.*
import org.junit.Test
import org.junit.Assert.*

class ScreenWorkspaceRegressionTest {
    @Test fun subtitlesNeverReplaceUnreadText() {
        val gate=CaptionGate()
        assertEquals("first",gate.offer("first",0))
        assertNull(gate.offer("second",1000))
        assertNull(gate.offer("third",1200))
        assertNull(gate.tick(2499))
        assertEquals("third",gate.tick(2500))
        assertNull(gate.offer("third",6000))
    }
    @Test fun longSubtitlesHaveEnoughDwell() {
        val gate=CaptionGate();val long="字幕".repeat(100)
        assertEquals(long,gate.offer(long,0));assertNull(gate.offer("next",5000));assertNull(gate.tick(9999));assertEquals("next",gate.tick(10000))
    }
    @Test fun genericModelActionMustBeSupported() {
        assertEquals(Step("type",2,"Ashley"),StepParser.parse("```json\n{\"action\":\"type\",\"node\":2,\"text\":\"Ashley\"}\n```"))
        assertNull(StepParser.parse("{\"action\":\"shell\",\"text\":\"rm -rf /\"}"))
    }
    @Test fun autoGlmUsesSafeDataParsing() {
        assertEquals(Step("tap",x=200,y=900),StepParser.parse("<answer>do(action=\"Tap\", element=[200,900])</answer>"))
        assertNull(StepParser.parse("do(action=\"Tap\", element=[2000,900])"))
        assertNull(StepParser.parse("__import__('os').system('whoami')"))
        assertEquals("已完成",StepParser.parse("finish(message=\"已完成\")")?.text)
    }
    @Test fun revokedSessionRejectsLateResults() {
        val id=ScreenState.begin();assertTrue(ScreenState.valid(id));ScreenState.end();assertFalse(ScreenState.valid(id))
        val next=ScreenState.begin();assertTrue(next>id);assertFalse(ScreenState.valid(id));ScreenState.end()
    }
}
