package ai.opennomi.app.voice

/** Only consume committed text. A cumulative server response must not be read twice. */
class SpeechChunks {
    private var consumed = 0
    fun take(text: String, flush: Boolean = false): List<String> {
        require(text.length <= 12000) { "FishAudio 回复过长，文字已保留" }
        check(text.length >= consumed) { "回答文字发生变化，请重新开始对话" }
        val result = mutableListOf<String>()
        while (consumed < text.length) {
            val limit = minOf(consumed + 150, text.length)
            val punctuation = (consumed until limit).firstOrNull { text[it] in "。！？；.!?;\n" &&
                !(text[it] == '.' && ((!flush && it+1==text.length) ||
                    (it > 0 && it+1 < text.length && text[it-1].isDigit() && text[it+1].isDigit()))) }
            var end = punctuation?.plus(1) ?: when {
                limit < text.length || limit-consumed == 150 -> limit
                flush -> text.length
                else -> break
            }
            if (end < text.length && Character.isHighSurrogate(text[end-1])) end--
            if (end <= consumed) break
            text.substring(consumed, end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            consumed = end
        }
        return result
    }
}

/** HTTP chunks can split a 16-bit sample, or the first format signature. */
class Pcm16Framer {
    private var prefix = ByteArray(0)
    private var verified = false
    private var pending: Byte? = null
    fun accept(bytes: ByteArray): ByteArray {
        var input = bytes
        if (!verified) {
            prefix += input
            if (prefix.size < 4) return ByteArray(0)
            val signature = String(prefix, 0, 4, Charsets.US_ASCII)
            require(signature !in setOf("RIFF", "OggS", "fLaC") && !signature.startsWith("ID3")) {
                "FishAudio 返回了封装音频，服务未支持 PCM；请关闭边收边播再试"
            }
            input = prefix; prefix = ByteArray(0); verified = true
        }
        val joined = pending?.let { byteArrayOf(it) + input } ?: input
        pending = if (joined.size % 2 == 1) joined.last() else null
        return joined.copyOf(joined.size - joined.size % 2)
    }
    fun finish() { require(verified && pending == null) { "FishAudio PCM 音频为空或样本不完整" } }
}
