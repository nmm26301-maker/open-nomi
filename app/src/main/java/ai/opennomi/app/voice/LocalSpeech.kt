package ai.opennomi.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** System Chinese speech is a fallback only; successful XiaoZhi audio keeps its original voice. */
class LocalSpeech(context: Context) {
    private val ready = CompletableDeferred<Int>()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val engine = TextToSpeech(context.applicationContext) { ready.complete(it) }
    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { id?.let { waiting.remove(it)?.complete(Unit) } }
            @Deprecated("Platform callback")
            override fun onError(id: String?) { failed(id) }
            override fun onError(id: String?, code: Int) { failed(id) }
            private fun failed(id: String?) { id?.let { waiting.remove(it)?.completeExceptionally(IllegalStateException("系统朗读失败，请检查中文语音引擎")) } }
        })
    }
    suspend fun speak(text: String) {
        check(withTimeout(10000) { ready.await() } == TextToSpeech.SUCCESS) { "系统语音引擎未就绪，请在系统文字转语音设置中安装中文语音" }
        check(engine.setLanguage(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE) { "系统缺少中文语音，请在文字转语音设置中安装" }
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        // Wait for actual playback completion, including long replies, before reopening the mic.
        for (part in text.take(12000).chunked(1800)) {
            if (part.isBlank()) continue
            val id = UUID.randomUUID().toString(); val done = CompletableDeferred<Unit>()
            waiting[id] = done
            try {
                check(engine.speak(part, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS) { "系统未接受朗读请求" }
                withTimeout((part.length * 450L + 15000L).coerceAtMost(180000L)) { done.await() }
            } finally { waiting.remove(id) }
        }
    }
    fun stop() { engine.stop(); waiting.values.forEach { it.cancel() }; waiting.clear() }
    fun release() { stop(); engine.shutdown() }
}
