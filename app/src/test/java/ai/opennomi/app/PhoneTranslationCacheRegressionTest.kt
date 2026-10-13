package ai.opennomi.app

import ai.opennomi.app.screen.PhoneTranslationCache
import org.junit.Assert.*
import org.junit.Test

class PhoneTranslationCacheRegressionTest {
    @Test fun exactSuccessfulRepeatReusesItsTranslation() {
        val c=PhoneTranslationCache();c.remember("en","hello","你好",c.epoch());assertEquals("你好",c.cached("en","hello"))
    }
    @Test fun aDifferentLanguageOrPunctuationCannotReuseTheResult() {
        val c=PhoneTranslationCache();c.remember("en","no!","不！",c.epoch());assertNull(c.cached("de","no!"));assertNull(c.cached("en","no?"));assertNull(c.cached("en","No!"))
    }
    @Test fun blanksAndOversizedInputsOrOutputsCannotEnterMemory() {
        val c=PhoneTranslationCache(maximumChars=5);c.remember("en","empty","",c.epoch());c.remember("en","123456","好",c.epoch());c.remember("en","small","字".repeat(6),c.epoch())
        assertNull(c.cached("en","empty"));assertNull(c.cached("en","123456"));assertNull(c.cached("en","small"))
    }
    @Test fun evictionPreservesRecentlyReadResultsWithinItsBound() {
        val c=PhoneTranslationCache(capacity=2);c.remember("en","a","甲",c.epoch());c.remember("en","b","乙",c.epoch());assertEquals("甲",c.cached("en","a"));c.remember("en","c","丙",c.epoch())
        assertNull(c.cached("en","b"));assertEquals("甲",c.cached("en","a"));assertEquals("丙",c.cached("en","c"))
    }
    @Test fun endingTheSessionClearsPreviouslyStoredSpeech() {
        val c=PhoneTranslationCache();c.remember("en","secret","秘密",c.epoch());c.clear();assertNull(c.cached("en","secret"))
    }
    @Test fun aLateTranslationCannotRepopulateClearedSessionMemory() {
        val c=PhoneTranslationCache();val old=c.epoch();c.clear();c.remember("en","old","旧",old);assertNull(c.cached("en","old"))
        c.remember("en","new","新",c.epoch());assertEquals("新",c.cached("en","new"))
    }
}
