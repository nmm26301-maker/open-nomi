package ai.opennomi.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import ai.opennomi.app.OpenNomiCloudViewModel
import ai.opennomi.app.model.ConversationState
import ai.opennomi.app.screen.ScreenState
import kotlinx.coroutines.*

/** Foreground microphone service owns recognition, independent of XiaoZhi.
 * Only final user speech can authorize execution. Old callback epochs are rejected.
 */
class NativePhoneControl(private val context: Context, private val model: OpenNomiCloudViewModel,
    private val scope: CoroutineScope, private val stopService: (String?) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val tasks = HandsFreeTasks(context)
    private val inbox = ControlInbox()
    private var recognizer: SpeechRecognizer? = null
    private var job: Job? = null
    private var epoch = 0
    private var enabled = false
    private var offline = false
    private var failures = 0
    private var taskEpoch = 0
    private var hasResult = false
    private var watchdog: Runnable? = null
    private var restart: Runnable? = null
    private val routes=(context.applicationContext as ai.opennomi.app.NomiApplication).audioRoutes
    private var routeLease: ai.opennomi.app.audio.ConversationAudioRoutes.Lease? = null
    private var routeJob: Job? = null
    fun start() {
        if (enabled) return
        offline = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        check(offline || SpeechRecognizer.isRecognitionAvailable(context)) { "手机没有可用的语音识别服务，请在系统语音输入设置中启用中文识别" }
        enabled = true
        model.beginNativeControl()
        publish("正在准备麦克风…")
        routeJob=scope.launch {
            try {
                routeLease=routes.acquire();routes.awaitReady()
                if(enabled)listen()
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(enabled)fatal("耳机音频未就绪：${e.message}")}
        }
    }
    private fun publish(message: String, state: ConversationState = ConversationState.LISTENING) {
        if(enabled) model.nativeControlStatus(message, state)
    }
    private fun retire() {
        epoch++
        watchdog?.let(main::removeCallbacks); watchdog = null
        val old = recognizer; recognizer = null
        runCatching { old?.cancel(); old?.destroy() }
    }
    private fun schedule(ms: Long) {
        retire()
        restart?.let(main::removeCallbacks)
        restart = Runnable { restart = null; if(enabled)listen() }.also { main.postDelayed(it, ms) }
    }
    private fun listen() {
        if(!enabled) return
        val token = ++epoch
        try {
            val engine = if(offline && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = engine
            fun current() = enabled && epoch == token && recognizer === engine
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if(current() && !hasResult)publish("我在听 · 直接说手机操作") }
                override fun onBeginningOfSpeech() { if(current())publish("正在听你的指令…") }
                override fun onRmsChanged(rmsdB: Float) { if(current())model.nativeControlLevel(((rmsdB + 2f) / 12f).coerceIn(0f,1f)) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    if(!current())return
                    watchdog?.let(main::removeCallbacks)
                    watchdog = Runnable { if(current())retry(5) }.also { main.postDelayed(it, 7000) }
                }
                override fun onError(error: Int) { if(current())retry(error) }
                override fun onResults(results: Bundle?) {
                    if(!current())return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    failures = 0
                    schedule(450)
                    if(text.isNotBlank()) {
                        model.nativeControlHeard(text)
                        submit(text)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if(current())model.nativeControlHeard(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
            watchdog = Runnable { if(current())retry(6) }.also { main.postDelayed(it, 65000) }
        } catch(e: Exception) {
            if(offline) { offline = false; schedule(800) }
            else fatal(e.message ?: "无法启动系统语音识别")
        }
    }
    private fun retry(error: Int) {
        if(!enabled) return
        if(offline && error in setOf(5,12,13)) { offline = false; failures = 0; publish("本机中文识别不可用，切换系统识别"); schedule(800); return }
        if(error in setOf(6,7)) { failures = 0; model.nativeControlLevel(0f) } else failures++
        val delay = NativeRecognitionPolicy.delay(error, failures)
        if(delay == null) fatal(NativeRecognitionPolicy.message(error))
        else { if(error !in setOf(6,7))publish(NativeRecognitionPolicy.message(error)); schedule(delay) }
    }
    private fun fatal(message: String) {
        ScreenState.event(message); model.nativeControlStatus(message, ConversationState.IDLE)
        close(); stopService(message)
    }
    private fun submit(text: String) {
        hasResult = true
        val sequence = VoiceTasks.parse(text)
        if(sequence?.error?.isNotBlank() == true) { publish(sequence.error); return }
        val request = NativeCommandRouter.route(text) ?: run { publish("没有识别到手机操作，可说打开微信、返回或输入文字"); return }
        val command = request.commands.singleOrNull()
        when(command?.action) {
            "stop" -> { close(); stopService(null); return }
            "cancel" -> { taskEpoch++;job?.cancel();job=null;inbox.clear();tasks.clear();publish("任务已取消，我继续听");return }
            "pause_task" -> {taskEpoch++;job?.cancel();job=null;inbox.clear();tasks.pause();publish("任务已暂停，可说继续任务");return}
        }
        tasks.continuation.rejection(request,job?.isActive==true)?.let { publish(it);return }
        if(job?.isActive == true) {
            publish(if(inbox.offer(request)) "指令已排队，可随时说取消任务" else "等待指令太多，请稍后再说")
            return
        }
        val taskToken = ++taskEpoch
        job = scope.launch {
            try {
                inbox.drain(request,{ tasks.continuation.canDrain }) { next ->
                    publish("正在操作手机…", ConversationState.THINKING)
                    val answer = if(next.goal != null) tasks.startAgent(next.goal) else tasks.execute(next.commands)
                    ScreenState.event(answer); publish(answer); answer
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {tasks.clear();inbox.clear();val message=e.message ?: "操作未完成";ScreenState.event(message);publish(message)}
            finally {if(taskToken == taskEpoch){job=null;ScreenState.update{it.copy(agentRunning=false,busy=false)}}}
        }
    }
    fun close() {
        routeJob?.cancel();routeJob=null;routeLease?.close();routeLease=null
        enabled = false;restart?.let(main::removeCallbacks);restart=null;retire()
        taskEpoch++;job?.cancel();job=null;inbox.clear();tasks.release();model.endNativeControl()
    }
}
