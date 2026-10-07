package ai.opennomi.app.voice

/** Handles retransmission and cumulative sentence events within one response. */
class ResponseAccumulator {
    private val seen = LinkedHashSet<String>()
    private var previous = ""
    private var assembled = ""
    fun reset() { seen.clear(); previous = "";assembled="" }
    fun accept(value: String): String {
        val text = value.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || !seen.add(text)) return ""
        // Some servers send cumulative LLM text followed by its last TTS sentence.
        if(assembled.isNotEmpty() && assembled.endsWith(text))return ""
        val delta = when {
            assembled.isNotEmpty() && text.startsWith(assembled) -> text.removePrefix(assembled)
            previous.isNotEmpty() && text.startsWith(previous) -> text.removePrefix(previous)
            previous.startsWith(text) -> ""
            else -> text
        }
        previous = text
        assembled += delta
        return delta
    }
}
