package ai.opennomi.app.screen

import java.util.ArrayDeque

data class AudioCaption(val translated:String,val original:String,val confidence:Double?,val language:String)

/** Keep fast translation bursts readable without applying the much longer OCR caption gate. */
class AudioCaptionPresenter(private val capacity:Int=2) {
    private val waiting=ArrayDeque<AudioCaption>()
    private var until=0L
    val canAccept get()=waiting.size<capacity
    val size get()=waiting.size
    fun offer(caption:AudioCaption,now:Long):AudioCaption? {
        require(caption.translated.isNotBlank() && caption.original.isNotBlank()){"翻译服务返回空字幕"}
        check(canAccept){"字幕展示队列已满"}
        waiting.addLast(caption)
        return tick(now)
    }
    fun tick(now:Long):AudioCaption? {
        if(now<until || waiting.isEmpty())return null
        val caption=waiting.removeFirst()
        until=now+readingTimeMs(caption.translated)
        return caption
    }
    fun reset(){waiting.clear();until=0L}

    companion object {
        /** CJK glyphs carry more information than Latin letters; retain a bounded live delay. */
        fun readingTimeMs(text:String):Long {
            val units=text.codePoints().toArray().sumOf { code ->
                when {
                    Character.isWhitespace(code) -> 0L
                    Character.UnicodeScript.of(code) in setOf(Character.UnicodeScript.HAN,
                        Character.UnicodeScript.HIRAGANA,Character.UnicodeScript.KATAKANA,
                        Character.UnicodeScript.HANGUL) -> 130L
                    else -> 40L
                }
            }
            return (300L+units).coerceIn(750L,4500L)
        }
    }
}

/** A confident majority must not hide one uncertain word, such as a name or a number. */
object SpeechConfidence {
    fun lowest(words:List<Double>):Double?=
        if(words.isEmpty() || words.any{!it.isFinite() || it !in 0.0..1.0})null else words.minOrNull()
}
