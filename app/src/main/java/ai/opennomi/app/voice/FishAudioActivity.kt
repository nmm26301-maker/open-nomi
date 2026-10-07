package ai.opennomi.app.voice

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ai.opennomi.app.NomiApplication
import ai.opennomi.app.ui.FishAudioDialog
import ai.opennomi.app.ui.FishHero
import ai.opennomi.app.web.FishAccountView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Standalone FishAudio reader. It calls FishAudio directly, with no XiaoZhi session required. */
class FishAudioActivity : ComponentActivity() {
    private var chooser: ValueCallback<Array<Uri>>? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)); chooser = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = (application as NomiApplication).cloudModel
        setContent {
            val settings = vm.fishSettings
            val scope = rememberCoroutineScope()
            var audioSession by remember { mutableIntStateOf(0) }
            var progress by remember { mutableFloatStateOf(0f) }
            val speaker = remember { FishSpeech(applicationContext, onPlayback = { audioSession = it },
                onProgress = { position, duration -> progress = if (duration > 0) position.toFloat() / duration else 0f }) }
            var tab by rememberSaveable { mutableIntStateOf(intent.getIntExtra("tab", 0).coerceIn(0,2)) }
            var text by rememberSaveable { mutableStateOf("你好，我可以独立朗读，也可以作为小智回复的声音。") }
            var busy by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf("") }
            var job by remember { mutableStateOf<Job?>(null) }
            var playbackSequence by remember { mutableIntStateOf(0) }
            var configure by remember { mutableStateOf(false) }
            var revision by remember { mutableIntStateOf(0) }
            var host by remember { mutableStateOf("fish.audio") }
            var webStatus by remember { mutableStateOf("") }
            var portal by remember { mutableStateOf<FishAccountView?>(null) }
            fun stop() { playbackSequence++; job?.cancel(); job = null; speaker.stop(); busy = false; progress = 0f }
            fun switchTab(next: Int) { stop(); tab = next }
            DisposableEffect(Unit) { onDispose { job?.cancel(); speaker.release(); portal?.dispose() } }
            BackHandler {
                if (tab == 2 && portal?.back() == true) Unit
                else if (tab != 0) switchTab(0)
                else { stop(); finish() }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB9A8FF), secondary = Color(0xFF7DD3FC),
                background = Color(0xFF0C101B), surface = Color(0xFF181D2C), onSurface = Color(0xFFF4F1FF))) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { stop(); finish() }) { Text("返回") }
                            Column(Modifier.weight(1f).padding(8.dp)) {
                                Text("FishAudio", fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                                Text("声音工作台", fontSize = 11.sp, color = Color(0xFF939BB6))
                            }
                            TextButton(onClick = { stop(); configure = true }) { Text("音色设置") }
                        }
                        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, divider = {}) {
                            listOf("独立朗读", "回复接入", "账号/申请").forEachIndexed { index, title ->
                                Tab(selected = tab == index, onClick = { switchTab(index) }, text = { Text(title) })
                            }
                        }
                        when (tab) {
                            0 -> Column(Modifier.weight(1f).imePadding()) {
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                FishHero(busy, audioSession, vm.voiceSettings.reduceMotion, settings.referenceId.ifBlank { "默认音色" })
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("让文字有声音", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                    Text("${text.length} / 12000", fontSize = 12.sp, color = Color(0xFF939BB6))
                                }
                                OutlinedTextField(text, { text = it.take(12000) }, enabled = !busy,
                                    placeholder = { Text("写下想听的话，或者粘贴一段文字…") }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                                    minLines = 4, shape = RoundedCornerShape(20.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AssistChip(onClick = { configure = true }, label = { Text(settings.model) })
                                    Text("独立使用 · 无需绑定小智", fontSize = 12.sp, color = Color(0xFF939BB6))
                                }
                                if (audioSession > 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                if (!settings.configured()) OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { switchTab(2) }) { Text("先在应用内申请密钥") }
                            }
                                if(result.isNotBlank())Text(result,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp),fontSize=13.sp,color=Color(0xFFABB5D0))
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(18.dp),
                                        enabled = !busy && text.isNotBlank(), onClick = {
                                        val draft = text
                                        val config = settings.connection()
                                        val sequence = ++playbackSequence
                                        busy = true; progress = 0f; result = "正在合成声音…"
                                        NomiVoiceService.stop(applicationContext);vm.pauseConversation()
                                        job = scope.launch {
                                            try { speaker.speak(draft, config) { result=it }; if (sequence == playbackSequence) result = "朗读播放完成" }
                                            catch (e: TimeoutCancellationException) { if (sequence == playbackSequence) result = "FishAudio 朗读超时，请检查网络后重试" }
                                            catch (e: CancellationException) { if(sequence==playbackSequence)result="朗读已停止，请重新点开始朗读";throw e }
                                            catch (e: Exception) { if (sequence == playbackSequence) result = e.message ?: "FishAudio 朗读失败" }
                                            finally { if (sequence == playbackSequence) { busy = false; job = null } }
                                        }
                                    }) { Text(if (busy) "正在朗读" else "开始朗读", fontWeight = FontWeight.SemiBold) }
                                    OutlinedButton(modifier = Modifier.height(54.dp), shape = RoundedCornerShape(18.dp), enabled = busy, onClick = { stop(); result = "已停止" }) { Text("停止") }
                                }
                            }
                            1 -> Column(Modifier.weight(1f).padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                FishHero(false, 0, true, "回复声音")
                                Text("同一个声音，陪你聊天", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                val connectedVoice = remember(revision) { settings.enabled && settings.configured() }
                                Card(shape = RoundedCornerShape(20.dp)) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(if (connectedVoice) "已接入聊天回复" else "尚未接入聊天回复", color = MaterialTheme.colorScheme.primary)
                                        Text(if (connectedVoice) "FishAudio 将朗读回复文字" else "独立朗读随时可用，开启接入后也能朗读聊天回复")
                                    }
                                }
                                Button(onClick = { configure = true }) { Text("配置音色并选择用于回复") }
                                Text("在音色设置中开启“用于聊天与屏幕回复”，保存后，小智负责回答文字，FishAudio 负责合成声音。")
                                Text("返回首页选择“聊天”即可对话；选择“手机控制”执行操作，执行后马上听下一条指令。")
                                OutlinedButton(onClick = { switchTab(2) }) { Text("内部申请密钥 / 选择音色") }
                            }
                            2 -> {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    TextButton(onClick = { portal?.open(FishPortalPolicy.KEYS) }) { Text("密钥") }
                                    TextButton(onClick = { portal?.open(FishPortalPolicy.VOICES) }) { Text("音色库") }
                                    TextButton(onClick = { portal?.reload() }) { Text("刷新") }
                                    TextButton(onClick = { configure = true }) { Text("填入密钥") }
                                }
                                Text("站点：$host", Modifier.padding(horizontal = 14.dp))
                                if (webStatus.isNotBlank()) Text(webStatus, Modifier.padding(horizontal = 14.dp))
                                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                                    val view = portal ?: FishAccountView(context, { webStatus = it }, { host = it }) { callback, params ->
                                        chooser?.onReceiveValue(null); chooser = callback
                                        runCatching { filePicker.launch(params.createIntent()) }.onFailure { chooser?.onReceiveValue(null); chooser = null; webStatus = "无法选择文件" }
                                    }.also { portal = it }
                                    // A tab can reattach the retained portal to a new Compose holder.
                                    (view.parent as? ViewGroup)?.removeView(view)
                                    view
                                })
                            }
                        }
                    }
                }
                if (configure) FishAudioDialog(vm, onClose = { configure = false; revision++ }, onApply = { configure = false; switchTab(2) })
            }
        }
    }
    override fun onDestroy() { chooser?.onReceiveValue(null); chooser = null; super.onDestroy() }
}
