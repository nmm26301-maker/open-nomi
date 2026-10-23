package ai.opennomi.app

import ai.opennomi.app.screen.*
import org.junit.Assert.*
import org.junit.Test

class HyMtRegressionTest {
    @Test fun translationKeepsNegationNumbersAndUnicodeInTheCurrentInput() {
        val source="Turn left, not right. 25 dollars. 会議は明日です。"
        val prompt=HyMtPrompt.create(source)
        assertTrue(prompt.endsWith(source));assertTrue(prompt.contains("只需要输出翻译后的结果"))
        assertFalse(prompt.contains("system"))
    }
    @Test fun previousSpeechCannotContaminateOrChangeCurrentTranslationInput() {
        val context="old ".repeat(200)+"The ticket costs 25 dollars. Do not open the door."
        val prompt=HyMtPrompt.create("Do open the door.",context)
        assertEquals(HyMtPrompt.create("Do open the door."),prompt)
        assertFalse(prompt.contains("25"));assertFalse(prompt.contains("Do not"))
        assertFalse(prompt.contains("背景信息"));assertTrue(prompt.endsWith("Do open the door."))
    }
    @Test fun emptyOrOversizedInputIsRejectedInsteadOfSilentlyTruncated() {
        for(input in listOf(" ","x".repeat(1001))) {
            try{HyMtPrompt.create(input);fail("Invalid input must be rejected")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun hyMtDraftAndFinalViewShowCorrectProviderAttribution() {
        val state=WorkspaceState(active=true,translation=true,audio=true,caption="不要开门",captionOriginal="Do not open the door.",
            captionDraft="下一",captionDraftOriginal="next",captionDraftLabel="临时译文 · Hy-MT2 正在生成")
        val rows=FloatingSubtitle.sections(state,true,true,true,"Hy-MT2 · 本机离线翻译")
        assertTrue(rows.any{it.text==state.captionDraftLabel})
        assertEquals("Hy-MT2 · 本机离线翻译",rows.last().text)
        assertFalse(rows.any{it.text.contains("Google")})
        assertTrue(rows.any{it.text=="不要开门"})
    }
}

