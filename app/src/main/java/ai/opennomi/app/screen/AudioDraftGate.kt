package ai.opennomi.app.screen

data class AudioDraftTicket(val epoch:Long,val text:String)

/** Partial hypotheses may retract words. Only preview a shared, complete-word prefix;
 * never promote it to a final caption or to history. Confined to the service main thread. */
class AudioDraftGate(private val intervalMs:Long=1200) {
    private var epoch=0L
    private var previous=emptyList<String>()
    private var current=emptyList<String>()
    private var candidate=""
    private var attempted=""
    private var lastAt:Long?=null
    fun observe(text:String) {
        val words=text.trim().split(Regex("\\s+")).filter{it.isNotEmpty()}
        if(words.isEmpty()){reset();return}
        previous=current;current=words
        val common=previous.zip(current).takeWhile{it.first==it.second}.map{it.first}
        // Leave the newest word out even when two callbacks agree on it.
        val stable=common.take(minOf(common.size,(current.size-1).coerceAtLeast(0)))
        candidate=stable.joinToString(" ").takeIf{stable.size>=3}.orEmpty()
        while(candidate.length>96)candidate=candidate.substringBeforeLast(' ',"")
    }
    fun next(now:Long):AudioDraftTicket? {
        if(candidate.isBlank() || candidate==attempted || lastAt?.let{now-it<intervalMs}==true)return null
        attempted=candidate;lastAt=now
        return AudioDraftTicket(epoch,candidate)
    }
    fun accepts(ticket:AudioDraftTicket):Boolean = ticket.epoch==epoch &&
        current.joinToString(" ").let{it==ticket.text || it.startsWith(ticket.text+" ")}
    fun reset(){epoch++;previous=emptyList();current=emptyList();candidate="";attempted=""}
}
