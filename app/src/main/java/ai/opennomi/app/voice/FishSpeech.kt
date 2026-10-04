package ai.opennomi.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.*
import java.io.File

/** One cancellable HTTP/playback session. A cancelled reply can never start late audio. */
class FishSpeech(context: Context, private val api: FishAudioApi = FishAudioApi()) {
    private val cache = context.applicationContext.cacheDir
    private var generation = 0
    private var player: MediaPlayer? = null
    private var completed: CompletableDeferred<Unit>? = null
    suspend fun speak(text: String, config: FishAudioConfig) {
        stop()
        val session = generation
        for (part in FishAudioApi.speechParts(text)) {
            if (part.isBlank()) continue
            val mp3 = api.synthesize(config, part)
            currentCoroutineContext().ensureActive()
            if (session != generation) throw CancellationException("FishAudio 播报已取消")
            val file = File.createTempFile("fish-", ".mp3", cache)
            try {
                withContext(Dispatchers.IO) { file.writeBytes(mp3) }
                if (session != generation) throw CancellationException("FishAudio 播报已取消")
                play(file, session)
            } finally { file.delete() }
        }
    }
    private suspend fun play(file: File, session: Int) {
        val done = CompletableDeferred<Unit>()
        val media = MediaPlayer()
        player = media; completed = done
        try {
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            media.setOnPreparedListener {
                if (session == generation && player === media) {
                    runCatching { media.start() }.onFailure { done.completeExceptionally(IllegalStateException("FishAudio 音频启动失败")) }
                } else done.cancel()
            }
            media.setOnCompletionListener { done.complete(Unit) }
            media.setOnErrorListener { _, _, _ -> done.completeExceptionally(IllegalStateException("FishAudio 音频播放失败，请检查音量和音色配置")); true }
            media.setDataSource(file.absolutePath)
            media.prepareAsync()
            withTimeout(180000) { done.await() }
        } finally {
            // stop() may have already released this player; it must not release a later one.
            if (player === media) { player = null; completed = null; runCatching { media.release() } }
        }
    }
    fun stop() {
        generation++
        completed?.cancel(); completed = null
        val old = player; player = null
        runCatching { old?.release() }
    }
    fun release() = stop()
}
