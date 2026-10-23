package ai.opennomi.app.screen

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Native lifecycle and every inference are serialized, including requests cancelled by callers. */
class HyMtTranslator {
    private val worker=Executors.newSingleThreadExecutor{r->Thread(r,"opennomi-hymt").apply{isDaemon=true}}
    private val lock=Any()
    private var handle=0L
    private var request=0L
    private var activeRequest=0L
    private val _status=MutableStateFlow("Hy-MT2 首次下载约 1.13 GB，之后可离线翻译")
    val status=_status.asStateFlow()
    private val cache=PhoneTranslationCache()
    fun clearCachedTranslations()=cache.clear()
    fun cancel() {HyMtModel.cancel();synchronized(lock){if(handle!=0L)HyMtNative.cancel(handle)}}

    private suspend fun <T> runNative(block:()->T):T=suspendCancellableCoroutine{continuation->
        val id=synchronized(lock){++request}
        continuation.invokeOnCancellation{synchronized(lock){if(activeRequest==id && handle!=0L)HyMtNative.cancel(handle)}}
        worker.execute {
            if(!continuation.isActive)return@execute
            synchronized(lock){activeRequest=id;if(handle!=0L)HyMtNative.reset(handle)}
            try {
                if(!continuation.isActive)return@execute
                val value=block()
                if(continuation.isActive)continuation.resume(value)
            }catch(t:Throwable){if(continuation.isActive)continuation.resumeWithException(t)}
            finally{synchronized(lock){if(activeRequest==id)activeRequest=0}}
        }
    }
    suspend fun prepare(context:Context,cellular:Boolean=false) {
        val job=currentCoroutineContext()[Job]!!
        _status.value="正在准备 Hy-MT2 离线翻译模型"
        val cancellation=job.invokeOnCompletion{if(job.isCancelled)HyMtModel.cancel()}
        val model=try{withContext(Dispatchers.IO){HyMtModel.prepare(context.applicationContext,cellular,{job.isActive},{_status.value=it})}}
            finally{cancellation.dispose()}
        currentCoroutineContext().ensureActive()
        runNative {
            if(synchronized(lock){handle}==0L) {
                _status.value="正在加载 Hy-MT2，请就绪后播放视频"
                val loaded=HyMtNative.open(model.absolutePath.toByteArray(Charsets.UTF_8),Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                check(loaded!=0L){"Hy-MT2 加载失败"}
                synchronized(lock){handle=loaded}
            }
            _status.value="Hy-MT2 已就绪 · 本机离线翻译"
        }
    }
    suspend fun translate(context:Context,text:String,cellular:Boolean=false,previous:String="",partial:((String)->Unit)?=null):String {
        val instruction=HyMtPrompt.create(text,previous)
        val key=text.trim()
        cache.cached("hymt2",key)?.let{return it}
        val epoch=cache.epoch()
        prepare(context,cellular)
        return withTimeout(90000) {runNative {
            _status.value="Hy-MT2 正在本机翻译"
            val result=HyMtNative.translate(synchronized(lock){handle},instruction.toByteArray(Charsets.UTF_8),object:HyMtNative.Sink {
                override fun onBytes(value:ByteArray){partial?.invoke(String(value,Charsets.UTF_8))}
            }).toString(Charsets.UTF_8).trim()
            check(result.isNotBlank()){"Hy-MT2 未返回译文"}
            cache.remember("hymt2",key,result,epoch)
            _status.value="翻译完成 · Hy-MT2 离线模型"
            result
        }}
    }
    fun release() {cancel();worker.execute{val old=synchronized(lock){val value=handle;handle=0L;value};if(old!=0L)HyMtNative.close(old);cache.clear()}}
    suspend fun close() {cancel();runNative {val old=synchronized(lock){val value=handle;handle=0L;value};if(old!=0L)HyMtNative.close(old);cache.clear()}}
}

