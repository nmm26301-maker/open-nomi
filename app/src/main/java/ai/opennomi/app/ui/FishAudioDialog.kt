package ai.opennomi.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.opennomi.app.OpenNomiCloudViewModel
import ai.opennomi.app.voice.FishAudioConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun FishAudioDialog(vm: OpenNomiCloudViewModel, onClose: () -> Unit) {
    val settings = vm.fishSettings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(settings.enabled) }
    var key by remember { mutableStateOf(settings.apiKey) }
    var voice by remember { mutableStateOf(settings.referenceId) }
    var model by remember { mutableStateOf(settings.model) }
    var base by remember { mutableStateOf(settings.base) }
    var speed by remember { mutableStateOf(settings.speed.toFloat()) }
    var message by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    fun config() = FishAudioConfig(key.trim(), voice.trim(), model.trim(), base.trim(), speed.toDouble())
    DisposableEffect(vm) { onDispose { previewJob?.cancel(); vm.stopFishPreview() } }
    AlertDialog(onDismissRequest = onClose, title = { Text("FishAudio 语音") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("用于聊天与屏幕回复", Modifier.weight(1f))
                Switch(enabled, { enabled = it })
            }
            Text("手机控制仍跳过播报。FishAudio 负责把小智的回答文字变成声音，不替代手机任务模型。")
            OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://fish.audio/app/api-keys"))) }) { Text("打开 FishAudio 获取密钥") }
            OutlinedTextField(voice, { voice = it }, label = { Text("音色 reference_id（可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("填写 FishAudio 音色页面的模型 ID 可选择音色；留空使用服务默认音色。使用需要账户权限与可用额度。")
            OutlinedTextField(model, { model = it }, label = { Text("合成模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("默认 s2.1-pro-free；也可填写账户支持的 s2.1-pro、s2-pro 或 s1。名称含 free 不代表账户额度无限。")
            Text("语速：${String.format(java.util.Locale.ROOT, "%.1f", speed)} 倍")
            Slider(speed, { speed = it }, valueRange = 0.5f..2f, steps = 14)
            OutlinedTextField(base, { base = it }, label = { Text("服务地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Text("默认使用 FishAudio 官方云端。兼容服务可填写 HTTPS 地址。聊天在播放完成后继续听；FishAudio 播报期间可点球球暂停。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !previewing, onClick = {
                    val draft = config()
                    message = "正在合成并试听…"; previewing = true
                    previewJob = scope.launch {
                        try { vm.previewFish(draft); message = "试听播放完成。保存后聊天回复将使用这个音色。" }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = e.message ?: "FishAudio 试听失败" }
                        finally { previewing = false; previewJob = null }
                    }
                }) { Text("试听") }
                if (previewing) TextButton(onClick = { previewJob?.cancel(); vm.stopFishPreview(); message = "试听已停止" }) { Text("停止") }
            }
            if (message.isNotBlank()) Text(message)
        }
    }, confirmButton = {
        TextButton(enabled = !previewing, onClick = {
            try { settings.save(config(), enabled); vm.pauseConversation(); onClose() }
            catch (e: Exception) { message = e.message ?: "FishAudio 配置保存失败" }
        }) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}
