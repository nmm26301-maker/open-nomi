package ai.opennomi.app.screen

/** Exact successful on-device results only; never reuse a contextual online response. */
class PhoneTranslationCache(private val capacity:Int=32,private val maximumChars:Int=1200) {
    private val entries=LinkedHashMap<Pair<String,String>,String>(capacity,.75f,true)
    private var generation=0L
    init{require(capacity>0 && maximumChars>0)}
    @Synchronized fun epoch()=generation
    @Synchronized fun cached(source:String,text:String):String?=entries[source to text]
    @Synchronized fun remember(source:String,text:String,translated:String,epoch:Long) {
        if(epoch!=generation || source.isBlank() || text.isBlank() || translated.isBlank() || text.length>maximumChars || translated.length>maximumChars)return
        entries[source to text]=translated
        while(entries.size>capacity)entries.remove(entries.keys.first())
    }
    @Synchronized fun clear(){entries.clear();generation++}
}
