package ai.opennomi.app.voice

/** Handles retransmission and cumulative sentence events within one response. */
class ResponseAccumulator {
    private val seen = LinkedHashSet<String>()
    private var previous = ""
    fun reset() { seen.clear(); previous = "" }
    fun accept(value: String): String {
        val text = value.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || !seen.add(text)) return ""
        val delta = when {
            previous.isNotEmpty() && text.startsWith(previous) -> text.removePrefix(previous)
            previous.startsWith(text) -> ""
            else -> text
        }
        previous = text
        return delta
    }
}
