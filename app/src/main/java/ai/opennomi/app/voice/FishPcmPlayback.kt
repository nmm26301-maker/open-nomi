package ai.opennomi.app.voice

import android.media.*
import android.os.SystemClock
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** One small streaming track. AudioTrack.write bounds both network and audio backlog. */
internal class FishPcmPlayback(private val duplex: Boolean, private val onStarted: (Int) -> Unit,
    private val onProgress: (Int, Int) -> Unit) {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var track: AudioTrack? = null
    private var written = 0L
    private var started = false
    private var progressAt = 0L
    fun write(bytes: ByteArray) {
        check(!closed.get()) { "FishAudio 播放已停止" }
        val target = synchronized(lock) {
            check(!closed.get()) { "FishAudio 播放已停止" }
            track ?: create().also { track = it }
        }
        var offset = 0
        while (offset < bytes.size && !closed.get()) {
            val count = target.write(bytes, offset, bytes.size-offset, AudioTrack.WRITE_BLOCKING)
            check(count > 0 && count % 2 == 0) { "FishAudio 音轨写入失败" }
            offset += count; written += count / 2
            if (!started && written >= FishAudioApi.PCM_RATE * 60 / 1000) start(target)
            val now = SystemClock.elapsedRealtime()
            if (now-progressAt >= 160) {
                progressAt=now
                onProgress((head(target)*1000/FishAudioApi.PCM_RATE).toInt(), 0)
            }
        }
        check(!closed.get()) { "FishAudio 播放已停止" }
    }
    private fun start(target: AudioTrack) {
        synchronized(lock) {
            if (closed.get() || started) return
            target.play(); started=true;onStarted(target.audioSessionId)
        }
    }
    suspend fun drain() = withContext(Dispatchers.IO) {
        val target = track ?: error("FishAudio 返回了空音频")
        if (!started) start(target)
        var last = head(target); var lastChange = SystemClock.elapsedRealtime()
        withTimeout(180000) {
            while (!closed.get() && last < written) {
                delay(20)
                val next = head(target)
                if (next != last) { last=next;lastChange=SystemClock.elapsedRealtime() }
                check(SystemClock.elapsedRealtime()-lastChange < 10000) { "FishAudio 音轨没有继续播放，请检查输出设备" }
            }
        }
        if (closed.get()) throw CancellationException("FishAudio 播放已停止")
    }
    private fun head(target: AudioTrack) = target.playbackHeadPosition.toLong() and 0xffffffffL
    private fun create(): AudioTrack {
        val rate = FishAudioApi.PCM_RATE
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "FishAudio 声音输出设备不可用" }
        return AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(if (duplex) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum*2,rate/5)).build().apply {
                check(state == AudioTrack.STATE_INITIALIZED) { "FishAudio 音轨初始化失败" }
                setBufferSizeInFrames(maxOf(minimum/2,rate/10))
                if (android.os.Build.VERSION.SDK_INT >= 31) setStartThresholdInFrames(1)
            }
    }
    fun close() {
        if (!closed.compareAndSet(false,true)) return
        synchronized(lock) {
            val old=track;track=null
            runCatching { old?.pause();old?.flush() };runCatching { old?.release() }
        }
    }
}
