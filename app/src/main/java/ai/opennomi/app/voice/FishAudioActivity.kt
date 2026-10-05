package ai.opennomi.app.voice

import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ai.opennomi.app.NomiApplication
import ai.opennomi.app.ui.FishAudioDialog
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
            val speaker = remember { FishSpeech(applicationContext) }
            var tab by rememberSaveable { mutableIntStateOf(intent.getIntExtra("tab", 0)) }
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
            fun stop() { playbackSequence++; job?.cancel(); job = null; speaker.stop(); busy = false }
            fun switchTab(next: Int) { stop(); tab = next }
            DisposableEffect(Unit) { onDispose { job?.cancel(); speaker.release(); portal?.dispose() } }
            BackHandler {
                if (tab == 2 && portal?.back() == true) Unit
                else if (tab != 0) switchTab(0)
                else { stop(); finish() }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7DD3FC), background = Color(0xFF070F19), surface = Color(0xFF142131))) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp)) {
                            TextButton(onClick = { stop(); finish() }) { Text("返回") }
                            Text("FishAudio", Modifier.weight(1f).padding(12.dp))
                            TextButton(onClick = { stop(); configure = true }) { Text("音色设置") }
                        }
                        TabRow(selectedTabIndex = tab) {
                            listOf("独立朗读", "回复接入", "账号/申请").forEachIndexed { index, title ->
                                Tab(selected = tab == index, onClick = { switchTab(index) }, text = { Text(title) })
                            }
                        }
                        when (tab) {
                            0 -> Column(Modifier.weight(1f).padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("直接使用 FishAudio，无需连接或绑定小智。")
                                OutlinedTextField(text, { text = it }, label = { Text("输入或粘贴要朗读的文字") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), minLines = 6)
                                Text("${text.length}/12000 字 · 使用音色设置中的模型与音色")
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(enabled = !busy && text.isNotBlank(), onClick = {
                                        val draft = text
                                        val config = settings.connection()
                                        val sequence = ++playbackSequence
                                        busy = true; result = "正在合成并播放…"
                                        vm.pauseConversation()
                                        job = scope.launch {
                                            try { speaker.speak(draft, config); if (sequence == playbackSequence) result = "朗读播放完成" }
                                            catch (e: TimeoutCancellationException) { if (sequence == playbackSequence) result = "FishAudio 朗读超时，请检查网络后重试" }
                                            catch (e: CancellationException) { throw e }
                                            catch (e: Exception) { if (sequence == playbackSequence) result = e.message ?: "FishAudio 朗读失败" }
                                            finally { if (sequence == playbackSequence) { busy = false; job = null } }
                                        }
                                    }) { Text("开始朗读") }
                                    OutlinedButton(enabled = busy, onClick = { stop(); result = "已停止" }) { Text("停止") }
                                }
                                if (result.isNotBlank()) Text(result)
                                if (!settings.configured()) TextButton(onClick = { switchTab(2) }) { Text("在 App 内注册并申请 API Key") }
                            }
                            1 -> Column(Modifier.weight(1f).padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("同一个 FishAudio 音色也可以接入回复播放。")
                                val connectedVoice = remember(revision) { settings.enabled && settings.configured() }
                                Text(if (connectedVoice) "当前已选 FishAudio 作为聊天声音" else "当前使用 NOMI 原声；FishAudio 仍可独立朗读")
                                Button(onClick = { configure = true }) { Text("配置音色并选择用于回复") }
                                Text("在音色设置中开启“用于聊天与屏幕回复”，保存后，小智负责回答文字，FishAudio 负责合成声音。")
                                Text("返回首页关闭“语音控制优先”可聊天。手机控制继续跳过播报，执行后马上听下一条指令。")
                                OutlinedButton(onClick = { switchTab(2) }) { Text("内部申请密钥 / 选择音色") }
                            }
                            2 -> {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                    TextButton(onClick = { portal?.open(FishPortalPolicy.KEYS) }) { Text("密钥") }
                                    TextButton(onClick = { portal?.open(FishPortalPolicy.VOICES) }) { Text("音色库") }
                                    TextButton(onClick = { portal?.reload() }) { Text("刷新") }
                                    TextButton(onClick = { configure = true }) { Text("填入密钥") }
                                }
                                Text("站点：$host", Modifier.padding(horizontal = 14.dp))
                                if (webStatus.isNotBlank()) Text(webStatus, Modifier.padding(horizontal = 14.dp))
                                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                                    (portal ?: FishAccountView(context, { webStatus = it }, { host = it }) { callback, params ->
                                        chooser?.onReceiveValue(null); chooser = callback
                                        runCatching { filePicker.launch(params.createIntent()) }.onFailure { chooser?.onReceiveValue(null); chooser = null; webStatus = "无法选择文件" }
                                    }.also { portal = it })
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
