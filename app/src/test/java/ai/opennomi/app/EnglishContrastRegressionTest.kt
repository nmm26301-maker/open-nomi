package ai.opennomi.app

import ai.opennomi.app.screen.EnglishContrast
import org.junit.Assert.*
import org.junit.Test

class EnglishContrastRegressionTest {
    @Test fun aSharedImperativeIsRestoredForDirectionsWithoutChangingTheirOrder() {
        assertEquals("turn to the left. Do not turn to the right.",EnglishContrast.complete("Turn left, not right."))
        assertEquals("go to the right. Do not go to the left.",EnglishContrast.complete("go right not left"))
        assertEquals("move up. Do not move down.",EnglishContrast.complete("Move up, rather than down."))
        assertEquals("Please look backward. Do not look forward.",EnglishContrast.complete("Please look backward, not forward!"))
    }
    @Test fun questionsNegativeCommandsAndNonDirectionalMeaningRemainUntouched() {
        listOf("Turn left, not right?","Don't turn left, not right.","Turn left, not only right.",
            "I turn left, not right.","This answer is not right.","Turn 25 times, not 50.",
            "Call Alice, not Bob.","Turn left if it is not right.","向左转，不要右转。",
            "Turn left and keep going, not right.").forEach{assertEquals(it,EnglishContrast.complete(it))}
    }
}
