package ai.opennomi.app.screen

/** A single latest screen line survives an in-flight request and a retry delay. */
class TranslationBuffer {
    private var pending = ""
    private var displayed = ""
    private var retryAt = 0L
    private var failures = 0
    private val cache = object : LinkedHashMap<String, String>(48, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 48
    }
    fun offer(text: String) { if (text.isNotBlank()) pending = text.trim() }
    fun next(now: Long): String? = pending.takeIf { now >= retryAt && it.isNotBlank() && it != displayed }
    fun cached(text: String) = cache[text]
    fun success(text: String, translated: String) {
        cache[text] = translated; displayed = text; retryAt = 0; failures = 0
    }
    fun failed(now: Long) {
        failures++; retryAt = now + (1000L shl minOf(failures, 4)).coerceAtMost(16000L)
    }
    fun forgetDisplayed() { displayed = "" }
    fun reset() { pending = ""; displayed = ""; retryAt = 0; failures = 0; cache.clear() }
}

object TranslationText {
    fun foreignLines(text: String): String = text.lineSequence().map(String::trim).filter { line ->
        val letters=line.count{it.isLetter()};val han=line.count{it in '\u4e00'..'\u9fff'}
        letters>=2 && (han==0 || han<letters*.55)
    }.toList().takeLast(5).joinToString("\n").take(1000)
}
