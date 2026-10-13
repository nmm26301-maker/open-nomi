package ai.opennomi.app.screen

import java.util.ArrayDeque

data class AudioSubtitle(val id:Long,val text:String,val confidence:Double?=null)
data class AudioSubtitleBatch(val utterances:List<AudioSubtitle>) {
    val text get()=utterances.joinToString("\n"){it.text}
    val confidence get()=if(utterances.any{it.confidence==null})null else utterances.mapNotNull{it.confidence}.minOrNull()
}

enum class SubtitleTiming(val label:String,val maximumMs:Long,val endSilenceSeconds:Float) {
    FAST("更快显示",3500,.45f), COMPLETE("完整语句",5500,.8f);
    companion object { fun selected(value:String)=entries.firstOrNull{it.name==value} ?: FAST }
}

/** Final utterances remain ordered even when speech arrives during a translation request. */
class AudioSubtitleQueue(private val capacity:Int=12) {
    private val pending=ArrayDeque<AudioSubtitle>()
    private var sequence=0L
    private var retryAt=0L
    private var failures=0
    private var selected:AudioSubtitleBatch?=null
    fun offer(text:String,confidence:Double?=null):Boolean {
        if(text.isBlank())return true
        if(pending.size>=capacity)return false
        pending.addLast(AudioSubtitle(++sequence,text.trim(),confidence));return true
    }
    fun next(now:Long):AudioSubtitle?=if(now>=retryAt)pending.peekFirst() else null
    fun nextBatch(now:Long):AudioSubtitleBatch? {
        if(now<retryAt || pending.isEmpty())return null
        selected?.let{return it}
        // Only combine already waiting speech; never wait to fill a batch or discard a sentence.
        val limit=if(pending.size>=4)3 else 1
        val items=mutableListOf<AudioSubtitle>();var length=0
        for(item in pending) {
            if(items.size>=limit || (items.isNotEmpty() && length+item.text.length>360))break
            items.add(item);length+=item.text.length+1
        }
        return AudioSubtitleBatch(items).also{selected=it}
    }
    fun success(batch:AudioSubtitleBatch) {
        if(selected!=batch || pending.take(batch.utterances.size).map{it.id}!=batch.utterances.map{it.id})return
        repeat(batch.utterances.size){pending.removeFirst()};selected=null;retryAt=0;failures=0
    }
    fun success(id:Long){if(pending.peekFirst()?.id==id){pending.removeFirst();selected=null;retryAt=0;failures=0}}
    fun failed(now:Long){failures++;retryAt=now+(500L shl minOf(failures,3)).coerceAtMost(4000L)}
    fun reset(){pending.clear();selected=null;retryAt=0;failures=0}
    val size get()=pending.size
}

/** Partial recognition is a preview only; it must not be treated as a settled translation. */
class AudioPhraseClock(private val maximumMs:Long=5500,private val graceMs:Long=1200) {
    private var started=-1L
    fun update(nonempty:Boolean,now:Long,atBoundary:Boolean=true):Boolean {
        if(!nonempty)return false
        if(started<0L)started=now
        val elapsed=now-started
        return elapsed>=maximumMs && (atBoundary || elapsed>=maximumMs+graceMs)
    }
    fun reset(){started=-1L}
}
