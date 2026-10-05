package ai.opennomi.app

import ai.opennomi.app.voice.FishPortalPolicy
import org.junit.Assert.*
import org.junit.Test

class FishPortalRegressionTest {
    @Test fun accountAndOAuthLinksStayEligibleForInternalNavigation() {
        assertTrue(FishPortalPolicy.allows(FishPortalPolicy.KEYS))
        assertTrue(FishPortalPolicy.allows(FishPortalPolicy.VOICES))
        assertTrue(FishPortalPolicy.allows("https://accounts.example.com/login?redirect_uri=https%3A%2F%2Ffish.audio%2Fcallback"))
        assertTrue(FishPortalPolicy.allows("about:blank"))
    }
    @Test fun refusesAppLaunchAndLocalSchemes() {
        for (url in listOf("intent://fish.audio/#Intent;scheme=https;end", "market://details?id=app", "mailto:test@example.com", "file:///private/secret", "content://files/secret", "javascript:alert(1)", "http://fish.audio", "https://name:password@fish.audio")) {
            assertFalse(url, FishPortalPolicy.allows(url))
        }
    }
    @Test fun displayedHostNeverContainsOAuthSecrets() {
        assertEquals("fish.audio", FishPortalPolicy.host("https://fish.audio/app/api-keys?code=private-token#secret"))
        assertEquals("", FishPortalPolicy.host("intent://fish"))
    }
}
