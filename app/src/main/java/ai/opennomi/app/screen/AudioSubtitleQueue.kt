package ai.opennomi.app.screen

import java.util.ArrayDeque

data class AudioSubtitle(val id:Long,val text:String,val confidence:Double?=null)

/** Final utterances remain ordered even when speech arrives during a translation request. */
class AudioSubtitleQueue(private val capacity:Int=12) {
    private val pending=ArrayDeque<AudioSubtitle>()
    private var sequence=0L
    private var retryAt=0L
    private var failures=0
    fun offer(text:String,confidence:Double?=null):Boolean {
        if(text.isBlank())return true
        if(pending.size>=capacity)return false
        pending.addLast(AudioSubtitle(++sequence,text.trim(),confidence));return true
    }
    fun next(now:Long):AudioSubtitle?=if(now>=retryAt)pending.peekFirst() else null
    fun success(id:Long){if(pending.peekFirst()?.id==id)pending.removeFirst();retryAt=0;failures=0}
    fun failed(now:Long){failures++;retryAt=now+(500L shl minOf(failures,3)).coerceAtMost(4000L)}
    fun reset(){pending.clear();retryAt=0;failures=0}
    val size get()=pending.size
}

/** Partial recognition is a preview only; it must not be treated as a settled translation. */
class AudioPhraseClock(private val maximumMs:Long=5500) {
    private var started=-1L
    fun update(nonempty:Boolean,now:Long):Boolean {
        if(!nonempty)return false
        if(started<0L)started=now
        return now-started>=maximumMs
    }
    fun reset(){started=-1L}
}
