package ai.opennomi.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

class RealtimeAudioEngine(
    private val context: Context,
    private val onEncodedFrame: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onUtteranceEnd: () -> Unit = {},
    private val onSpeechDetected: () -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onRealtimeCapability: (Boolean) -> Unit = {},
) {
    companion object { const val SAMPLE_RATE = 16000; const val CHANNELS = 1; const val FRAME_MS = 60; const val FRAME_SAMPLES = 960 }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureGeneration = AtomicInteger()
    private val serverGeneration = AtomicInteger()
    private val trackLock = Any()
    @Volatile private var record: AudioRecord? = null
    @Volatile private var serverTrack: AudioTrack? = null
    private var serverWritten = 0L
    private var serverStarted = false
    @Volatile private var serverRate = SAMPLE_RATE
    fun configureServerAudio(rate: Int) { require(rate in setOf(8000,12000,16000,24000,48000)); stopServerVoice(); serverRate = rate }
    private var decoderGeneration = -1
    private var decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
    // The serial playback worker owns this buffer; never allocate 11KB per Opus frame.
    private val decodePcm = ShortArray(5760)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private sealed interface Command {
        val generation: Int
        data class Packet(override val generation: Int, val bytes: ByteArray) : Command
        data class Drain(override val generation: Int, val done: CompletableDeferred<Unit>) : Command
    }
    private val serverFrames = Channel<Command>(512)
    init {
        scope.launch {
            for (command in serverFrames) {
                if (command is Command.Drain) {
                    try { if (command.generation == serverGeneration.get()) {
                        val target = synchronized(trackLock) { serverTrack?.also { startServer(it, true) } }
                        waitTrack(target, { serverWritten }, { command.generation == serverGeneration.get() })
                    }
                    } catch (e: Exception) { if (command.generation == serverGeneration.get()) onError("音轨等待失败：${e.message}") }
                    finally { command.done.complete(Unit) }
                } else if (command is Command.Packet && command.generation == serverGeneration.get()) {
                    try { playPacket(command.generation, command.bytes) }
                    catch (e: Exception) { if (command.generation == serverGeneration.get()) onError("语音播放失败：${e.message.orEmpty()}") }
                }
            }
        }
    }
    fun hasRecordPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun supportsRealtime() = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
    private fun communicationAudio() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // Respect a connected headset instead of forcing all playback onto the speaker.
        if (!audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in intArrayOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET) }) {
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = true }
        }
    }
    fun startRecording(realtime: Boolean = false, encodeCapture: Boolean = true,silenceMillis:Long=700): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        stopRecording()
        val run = captureGeneration.incrementAndGet()
        var input: AudioRecord? = null
        var echo: AcousticEchoCanceler? = null
        var noise: NoiseSuppressor? = null
        try {
            communicationAudio()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            input = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_SAMPLES * 8))
            check(input.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            if (supportsRealtime()) echo = OptionalAudioEffect.create({ AcousticEchoCanceler.create(input.audioSessionId) }, { it.enabled = true; it.enabled }, { it.release() })
            val concurrentCapture = realtime && echo?.enabled == true
            onRealtimeCapability(concurrentCapture)
            if (runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)) noise = OptionalAudioEffect.create({ NoiseSuppressor.create(input.audioSessionId) }, { it.enabled = true; it.enabled }, { it.release() })
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风没有开始录音" }
            record = input
            val active = input; val activeEcho = echo; val activeNoise = noise
            scope.launch {
                val encoder = if (encodeCapture) OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP).apply { bitrate = 24000 } else null
                val gate = SpeechGate(silenceMillis,if(realtime)3 else 6) { SystemClock.elapsedRealtime() }
                val frame = ShortArray(FRAME_SAMPLES); val encoded = ByteArray(1500)
                try {
                    while (run == captureGeneration.get()) {
                        var offset = 0
                        while (offset < frame.size && run == captureGeneration.get()) {
                            val count = active.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                            check(count > 0) { "麦克风读取失败" }; offset += count
                        }
                        if (run != captureGeneration.get()) break
                        val rms = rawRms(frame, frame.size); onLevel((rms * 7).coerceAtMost(1f))
                        val size = encoder?.encode(frame, 0, FRAME_SAMPLES, encoded, 0, encoded.size) ?: 0
                        if (size > 0 && run == captureGeneration.get()) onEncodedFrame(encoded.copyOf(size))
                        if (gate.accept(rms) && concurrentCapture) onSpeechDetected()
                        if (gate.consumeEnd()) { onUtteranceEnd(); if(!concurrentCapture)break }
                    }
                } catch (e: Exception) { if (run == captureGeneration.get()) onError("录音失败：${e.message.orEmpty()}") }
                finally {
                    runCatching { active.stop() }; runCatching { active.release() }
                    runCatching { activeEcho?.release() }; runCatching { activeNoise?.release() }
                    if (record === active) record = null
                }
            }
            return true
        } catch (e: Exception) {
            runCatching { input?.release() }; runCatching { echo?.release() }; runCatching { noise?.release() }
            restoreAudioMode(); onError(e.message ?: "麦克风启动失败"); return false
        }
    }
    fun stopRecording() { captureGeneration.incrementAndGet(); val old = record; record = null; runCatching { old?.stop() }; onLevel(0f) }
    fun isRecording() = record != null
    fun playServerOpus(opus: ByteArray) {
        if (!serverFrames.trySend(Command.Packet(serverGeneration.get(), opus.copyOf())).isSuccess) {
            stopServerVoice(); onError("语音播放积压过多，请重新开始对话")
        }
    }
    private fun playPacket(run: Int, opus: ByteArray) {
        if (run != decoderGeneration) { decoder = OpusDecoder(serverRate, CHANNELS); decoderGeneration = run }
        val pcm = decodePcm
        val samples = decoder.decode(opus, 0, opus.size, pcm, 0, pcm.size, false)
        if (samples <= 0 || run != serverGeneration.get()) return
        val track = synchronized(trackLock) {
            if (run != serverGeneration.get()) return
            serverTrack ?: createTrack(serverRate).also { serverTrack = it; serverWritten = 0; serverStarted = false }
        }
        var offset = 0
        while (offset < samples && run == serverGeneration.get()) {
            val wrote = track.write(pcm, offset, minOf(samples - offset, 960), AudioTrack.WRITE_BLOCKING)
            check(wrote > 0) { "音轨写入失败" }; offset += wrote
            synchronized(trackLock) { if (run == serverGeneration.get() && serverTrack === track) { serverWritten += wrote; startServer(track, false) } }
        }
        if (run == serverGeneration.get()) onLevel((rawRms(pcm, samples) * 7).coerceAtMost(1f))
    }
    private fun startServer(track: AudioTrack, force: Boolean) {
        if (!serverStarted && (force || serverWritten >= serverRate * 120 / 1000)) { track.play(); serverStarted = true }
    }
    suspend fun awaitServerPlayback() {
        val done = CompletableDeferred<Unit>()
        serverFrames.send(Command.Drain(serverGeneration.get(), done)); withTimeout(12000) { done.await() }
    }
    private suspend fun waitTrack(track: AudioTrack?, written: () -> Long, valid: () -> Boolean) {
        if (track == null) return
        val drained = withTimeoutOrNull(10000) {
            while (valid() && (track.playbackHeadPosition.toLong() and 0xffffffffL) < written()) delay(20)
        }
        if (drained == null && valid()) error("音轨未按时播放完，请检查输出设备")
    }
    fun stopServerVoice() = synchronized(trackLock) { serverGeneration.incrementAndGet(); val old = serverTrack; serverTrack = null; releaseTrack(old); serverWritten = 0; serverStarted = false }
    private fun releaseTrack(track: AudioTrack?) { runCatching { track?.pause(); track?.flush() }; runCatching { track?.release() } }
    fun stopAllPlayback() { stopServerVoice(); onLevel(0f) }
    fun restoreAudioMode() { if (record == null) audioManager.mode = AudioManager.MODE_NORMAL }
    fun release() { stopRecording(); stopAllPlayback(); serverFrames.close(); scope.cancel(); audioManager.mode = AudioManager.MODE_NORMAL }
    private fun createTrack(rate: Int): AudioTrack {
        communicationAudio()
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "声音输出设备不可用" }
        return AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum * 3, rate)).build().apply {
                check(state == AudioTrack.STATE_INITIALIZED) { "声音音轨初始化失败" }
                setBufferSizeInFrames(maxOf(minimum / 2, rate / 5))
                if (android.os.Build.VERSION.SDK_INT >= 31) setStartThresholdInFrames(1)
            }
    }
    private fun rawRms(pcm: ShortArray, count: Int): Float { var sum = 0.0; for (i in 0 until count) { val x = pcm[i] / 32768.0; sum += x*x }; return if (count == 0) 0f else sqrt(sum/count).toFloat() }
}
