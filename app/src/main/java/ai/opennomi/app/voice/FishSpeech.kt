package ai.opennomi.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.AudioFocusRequest
import kotlinx.coroutines.*
import java.io.File

/** One cancellable HTTP/playback session. A cancelled reply can never start late audio. */
class FishSpeech(context: Context, private val api: FishAudioApi = FishAudioApi(),
    private val onPlayback: (Int) -> Unit = {},
    private val onProgress: (Int, Int) -> Unit = { _, _ -> },
    private val onStage: (String) -> Unit = {}) {
    private val cache = context.applicationContext.cacheDir
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var focus:AudioFocusRequest?=null
    @Volatile private var generation = 0
    private var player: MediaPlayer? = null
    private var completed: CompletableDeferred<Unit>? = null
    private var requestJob: Job? = null
    private var pcm: FishPcmPlayback? = null
    suspend fun speak(text: String, config: FishAudioConfig, duplex:Boolean=false, stage:(String)->Unit = onStage) = withContext(Dispatchers.Main.immediate) {
        stop()
        config.validate()
        val volumeStream=if(duplex)AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        check(audio.getStreamVolume(volumeStream)>0 && !audio.isStreamMute(volumeStream)) {
            if(duplex) "通话音量为零或已静音，请调高通话音量" else "媒体音量为零或已静音，请调高媒体音量后再试听"
        }
        val session = generation
        val job=currentCoroutineContext().job;requestJob=job
        try {
        if(config.streaming) {stream(text,config,session,stage,duplex);return@withContext}
        val parts=FishAudioApi.speechParts(text)
        for ((index,part) in parts.withIndex()) {
            if (part.isBlank()) continue
            stage("请求 FishAudio · 第${index+1}/${parts.size}段（最多等待65秒）")
            val mp3 = try { withTimeout(65000) { api.synthesize(config, part) } }
                catch(e:TimeoutCancellationException) {throw IllegalStateException("FishAudio 合成请求超时，请检查网络与服务地址")}
            currentCoroutineContext().ensureActive()
            if (session != generation) throw CancellationException("FishAudio 播报已取消")
            val file = File.createTempFile("fish-", ".mp3", cache)
            try {
                withContext(Dispatchers.IO) { file.writeBytes(mp3) }
                if (session != generation) throw CancellationException("FishAudio 播报已取消")
                stage("已收到音频 ${mp3.size/1024} KB · 准备播放")
                play(file, session,stage)
            } finally { file.delete() }
        }
        } finally {if(requestJob===job)requestJob=null}
    }
    private suspend fun stream(text:String,config:FishAudioConfig,session:Int,stage:(String)->Unit,duplex:Boolean) {
        val handler=android.os.Handler(android.os.Looper.getMainLooper())
        fun current(block:()->Unit) {handler.post {if(session==generation)block()}}
        val attributes=AudioAttributes.Builder().setUsage(if(duplex)AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener {change ->
                if(change==AudioManager.AUDIOFOCUS_LOSS && session==generation)stop()
            }.build()
        check(audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {"无法获得音频输出，请结束其他通话或播放后重试"}
        focus=request
        val startedAt=android.os.SystemClock.elapsedRealtime()
        val output=FishPcmPlayback(duplex,{id->current {
            onPlayback(id)
            stage("正在播放 · 首段音频 ${android.os.SystemClock.elapsedRealtime()-startedAt} ms${if(duplex)" · 可开口打断" else ""}")
        }},{position,duration->current {onProgress(position,duration)}})
        pcm=output
        try {
            val parts=SpeechChunks().take(text,flush=true)
            for((index,part) in parts.withIndex()) {
                stage("请求 FishAudio · 第${index+1}/${parts.size}段 · 边收边播")
                try {withTimeout(65000){api.streamPcm(config,part){bytes->
                    if(session!=generation)throw CancellationException("FishAudio 播放已停止")
                    output.write(bytes)
                }}} catch(e:TimeoutCancellationException){throw IllegalStateException("FishAudio 流式请求超时，请检查网络与服务地址")}
                currentCoroutineContext().ensureActive()
            }
            output.drain()
        } finally {
            output.close()
            if(pcm===output){pcm=null;onPlayback(0);releaseFocus()}
        }
    }
    private suspend fun play(file: File, session: Int, stage:(String)->Unit) {
        val done = CompletableDeferred<Unit>()
        val prepared=CompletableDeferred<Unit>()
        val media = MediaPlayer()
        player = media; completed = done
        try {
            val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                    if(change==AudioManager.AUDIOFOCUS_LOSS && player===media)
                        done.completeExceptionally(IllegalStateException("音频输出被其他应用占用，请结束通话或其他播放后重试"))
                }.build()
            check(audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {"无法获得音频输出，请结束其他通话或播放后重试"}
            focus=request
            media.setAudioAttributes(attributes)
            media.setOnPreparedListener {
                if (session == generation && player === media) {
                    prepared.complete(Unit)
                } else prepared.cancel()
            }
            media.setOnCompletionListener { done.complete(Unit) }
            media.setOnErrorListener { _, what, extra ->
                val error=IllegalStateException("FishAudio 音频解码失败（$what/$extra），请检查合成格式")
                prepared.completeExceptionally(error);done.completeExceptionally(error);true
            }
            media.setDataSource(file.absolutePath)
            media.prepareAsync()
            try {withTimeout(15000){prepared.await()}}
                catch(e:TimeoutCancellationException){throw IllegalStateException("FishAudio 音频准备超时，请重试")}
            currentCoroutineContext().ensureActive()
            if(session!=generation)throw CancellationException("FishAudio 播报已取消")
            media.start();onPlayback(media.audioSessionId)
            stage("正在播放 · 媒体音量 ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
            coroutineScope {
                val progress = launch {
                    while (isActive && !done.isCompleted) {
                        if (session == generation && player === media)
                            runCatching { onProgress(media.currentPosition, media.duration.coerceAtLeast(0)) }
                        delay(160)
                    }
                }
                try { withTimeout(180000) { done.await() } } finally { progress.cancel() }
            }
        } finally {
            // stop() may have already released this player; it must not release a later one.
            if (player === media) { player = null; completed = null; onPlayback(0); runCatching { media.release() };releaseFocus() }
        }
    }
    fun stop() {
        generation++
        requestJob?.cancel();requestJob=null
        val oldPcm=pcm;pcm=null;oldPcm?.close()
        completed?.cancel(); completed = null
        val old = player; player = null
        onPlayback(0)
        runCatching { old?.release() }
        releaseFocus()
    }
    private fun releaseFocus() {focus?.let { audio.abandonAudioFocusRequest(it) };focus=null}
    fun release() = stop()
}
