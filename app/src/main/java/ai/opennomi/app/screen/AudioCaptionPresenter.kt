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
        until=now+(caption.translated.length*18L).coerceIn(750L,1500L)
        return caption
    }
    fun reset(){waiting.clear();until=0L}
}

/** A confident majority must not hide one uncertain word, such as a name or a number. */
object SpeechConfidence {
    fun lowest(words:List<Double>):Double?=
        if(words.isEmpty() || words.any{!it.isFinite() || it !in 0.0..1.0})null else words.minOrNull()
}
