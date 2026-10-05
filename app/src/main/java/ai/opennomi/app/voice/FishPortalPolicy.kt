package ai.opennomi.app.voice

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Web and OAuth links stay in the embedded view; application/deep links are not launched. */
object FishPortalPolicy {
    const val KEYS = "https://fish.audio/app/developers/"
    const val VOICES = "https://fish.audio/app/discovery/"
    fun allows(url: String): Boolean {
        if (url == "about:blank") return true
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty()
    }
    fun host(url: String) = url.toHttpUrlOrNull()?.host.orEmpty()
}
