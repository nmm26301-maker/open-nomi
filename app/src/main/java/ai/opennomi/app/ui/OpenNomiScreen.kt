package ai.opennomi.app.ui

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.opennomi.app.OpenNomiCloudViewModel
import ai.opennomi.app.model.ConversationState
import ai.opennomi.app.web.AiriActivity
import ai.opennomi.app.web.XiaozhiAccountView
import kotlinx.coroutines.delay

private val Background = Color(0xFF070F19)
private val Mint = Color(0xFF7DD3FC)
private val Secondary = Color(0xFF869598)
private val Panel = Color(0xFF142131)

@Composable
fun OpenNomiApp(vm: OpenNomiCloudViewModel, onTalk: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val connected by vm.connected.collectAsStateWithLifecycle()
    val connecting by vm.connecting.collectAsStateWithLifecycle()
    val pendingStart by vm.pendingStart.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val emotion by vm.emotion.collectAsStateWithLifecycle()
    val heard by vm.heard.collectAsStateWithLifecycle()
    val response by vm.response.collectAsStateWithLifecycle()
    val pairing by vm.pairingCode.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }
    var voiceRevision by remember { mutableStateOf(0) }
    val voiceLabel = remember(voiceRevision) { if (vm.usesFishVoice()) "FishAudio" else "NOMI 原声" }
    val phoneControl = remember(voiceRevision) { vm.voiceSettings.phoneControl }
    var account by remember { mutableStateOf(false) }
    var airi by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    var reduceMotion by remember { mutableStateOf(vm.voiceSettings.reduceMotion) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { foreground = true; airi = false; voiceRevision++ }
            if (event == Lifecycle.Event.ON_PAUSE) foreground = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(airi) {
        if (airi) { delay(if (reduceMotion) 0 else 170); vm.pauseConversation(); context.startActivity(Intent(context, AiriActivity::class.java)) }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Background, surface = Panel, onSurface = Color(0xFFF4F4EF))) {
        Surface(color = Background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                ModeTabs(airi, reduceMotion) { airi = it }
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OpenNomi", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (connected) Mint else Secondary))
                            Text("  0.48 · ${if(phoneControl) "手机语音控制" else if (connecting) "连接中" else if (connected) "在线" else "未连接"}", color = Secondary, fontSize = 12.sp)
                        }
                    }
                    Text(voiceLabel, color = Mint, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                    TextButton(onClick = { settings = true }, contentPadding = PaddingValues(10.dp)) { Text("设置", fontSize = 14.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=22.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected=!phoneControl,onClick={vm.setPhoneControl(false);voiceRevision++},label={Text("聊天")},modifier=Modifier.weight(1f))
                    FilterChip(selected=phoneControl,onClick={vm.setPhoneControl(true);voiceRevision++},label={Text("手机控制")},modifier=Modifier.weight(1f))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        val size = minOf(maxWidth + 12.dp, maxHeight)
                        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                            Aura(state, foreground && !reduceMotion, Modifier.fillMaxSize())
                            Ball(emotion, foreground, reduceMotion, Modifier.fillMaxSize()) { if (!phoneControl && pairing != null) account = true else onTalk() }
                        }
                    }
                    AnimatedContent(targetState = status, label = "conversation status", transitionSpec = {
                        if (reduceMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        else (fadeIn(tween(220)) + slideInVertically { it / 5 }) togetherWith fadeOut(tween(100))
                    }, modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) { message ->
                        Text(message, fontSize = 21.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(when (state) {
                        ConversationState.LISTENING -> if(phoneControl) "说完直接操作 · 点球球暂停" else "说完会自动回应，点球球可暂停"
                        ConversationState.THINKING -> if(phoneControl) "正在操作 · 可说取消任务" else "正在准备回答"
                        ConversationState.SPEAKING -> "陪你聊一会儿"
                        else -> "我在这儿"
                    }, fontSize = 13.sp, color = Secondary, modifier = Modifier.padding(top = 9.dp, bottom = 16.dp))
                    LevelBars(vm, state, Modifier.height(24.dp).width(56.dp))
                    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 84.dp).padding(horizontal = 26.dp, vertical = 9.dp)) {
                        val text = response.ifBlank { heard }
                        if (text.isNotBlank()) Text(text, color = Color(0xFFBAC7C5), fontSize = 14.sp, textAlign = TextAlign.Center,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        if (!phoneControl && pairing != null) TextButton(onClick = { account = true }, modifier = Modifier.align(Alignment.Center)) { Text("绑定设备 · 验证码 $pairing") }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("vision" to "看屏幕", "tasks" to "Agent", "fragments" to "碎片本", "fish" to "FishAudio").forEach { (key, label) ->
                        OutlinedButton(onClick = { if (key == "fish") context.startActivity(Intent(context, ai.opennomi.app.voice.FishAudioActivity::class.java)) else context.startActivity(Intent(context, ai.opennomi.app.screen.WorkspaceActivity::class.java).putExtra("tab", key)) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, fontSize = 13.sp, maxLines = 1) }
                    }
                }
                TalkButton(state, connecting && !phoneControl, pendingStart && !phoneControl, { if (!phoneControl && pairing != null) account = true else onTalk() }, Modifier.padding(horizontal = 22.dp).padding(top = 10.dp), reduceMotion)
                Text("${if (state == ConversationState.IDLE) "轻点球球开始 · 首次请允许麦克风与手电筒权限" else "可说：打开手电筒、点个赞、返回 · 轻点暂停"}", color = Secondary,
                    fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp))
            }
        }
        if (settings) SettingsDialog(vm, reduceMotion, { reduceMotion = it; vm.voiceSettings.reduceMotion = it }, { settings = false; voiceRevision++ }, { settings = false; account = true })
        if (account) AccountDialog({ account = false }) { account = false; vm.disconnect(); vm.connect() }
    }
}

@Composable
private fun ModeTabs(airi: Boolean, reduceMotion: Boolean, onSelect: (Boolean) -> Unit) {
    val progress by animateFloatAsState(if (airi) 1f else 0f, if (reduceMotion) tween(0) else spring(dampingRatio = .8f, stiffness = 420f), label = "tab slide")
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 12.dp).height(50.dp).clip(RoundedCornerShape(25.dp)).background(Panel)) {
        val tabWidth = (maxWidth - 8.dp) / 2
        Box(Modifier.padding(4.dp).offset(x = tabWidth * progress).width(tabWidth).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Mint.copy(alpha = .15f)).border(1.dp, Mint.copy(alpha = .18f), RoundedCornerShape(22.dp)))
        Row(Modifier.fillMaxSize()) {
            listOf("NOMI", "AIRI").forEachIndexed { index, title ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable { onSelect(index == 1) }, contentAlignment = Alignment.Center) {
                    Text(title, color = if ((index == 1) == airi) Mint else Secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Aura(state: ConversationState, animate: Boolean, modifier: Modifier) {
    val color by animateColorAsState(when (state) {
        ConversationState.THINKING -> Color(0xFFB4A7E6)
        ConversationState.SPEAKING -> Color(0xFFBFECCF)
        else -> Mint
    }, tween(450), label = "aura color")
    val glow = remember(color) { Brush.radialGradient(listOf(color.copy(alpha = .09f), Color.Transparent)) }
    if (animate) {
        val cycle = rememberInfiniteTransition(label = "gentle breath")
        val breath by cycle.animateFloat(.86f, 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
        Canvas(modifier) { drawCircle(glow, radius = size.minDimension * .5f, alpha = breath) }
    }
}

@Composable
private fun Ball(emotion: String, foreground: Boolean, reduceMotion: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AndroidView(factory = { EmotionBallView(it) }, modifier = modifier,
        onRelease = { it.dispose() }, update = {
            it.setMood(emotion); it.setMotionEnabled(!reduceMotion)
            if (foreground) it.resumeAnimation() else it.pauseAnimation()
            it.setOnClickListener { onClick() }
        })
}

@Composable
private fun LevelBars(vm: OpenNomiCloudViewModel, state: ConversationState, modifier: Modifier) {
    // Collect the high-frequency level only here, without updating the WebView or whole page.
    val value by vm.level.collectAsStateWithLifecycle()
    val level by animateFloatAsState(if (state == ConversationState.IDLE) 0f else value.coerceIn(0f, 1f), tween(100), label = "voice level")
    Canvas(modifier) {
        for (i in 0..4) {
            val factor = 1f - .3f * kotlin.math.abs(i - 2)
            val height = 3.dp.toPx() + (size.height - 3.dp.toPx()) * level * factor
            drawLine(if (state == ConversationState.IDLE) Secondary.copy(alpha = .38f) else Mint, Offset(size.width * (i + .5f) / 5, (size.height - height) / 2), Offset(size.width * (i + .5f) / 5, (size.height + height) / 2), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun TalkButton(state: ConversationState, connecting: Boolean, pendingStart: Boolean, onClick: () -> Unit, modifier: Modifier, reduceMotion: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, if (reduceMotion) tween(0) else spring(stiffness = 600f), label = "button press")
    val idle = state == ConversationState.IDLE
    Button(onClick = onClick, interactionSource = interaction, shape = RoundedCornerShape(30.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (idle) Color(0xFF625577) else Mint, contentColor = if (idle) Color.White else Background),
        modifier = modifier.fillMaxWidth().height(60.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Canvas(Modifier.size(20.dp)) {
            val ink = if (idle) Color.White else Background
            if (!idle) { drawLine(ink, Offset(size.width * .35f, size.height * .2f), Offset(size.width * .35f, size.height * .8f), 3.dp.toPx(), StrokeCap.Round); drawLine(ink, Offset(size.width * .65f, size.height * .2f), Offset(size.width * .65f, size.height * .8f), 3.dp.toPx(), StrokeCap.Round) }
            else { drawRoundRect(ink, topLeft = Offset(size.width * .35f, 0f), size = Size(size.width * .3f, size.height * .65f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 6), style = Stroke(1.6.dp.toPx())); drawArc(ink, 0f, 180f, false, Offset(size.width * .18f, size.height * .18f), Size(size.width * .64f, size.height * .58f), style = Stroke(1.6.dp.toPx())); drawLine(ink, Offset(size.width / 2, size.height * .76f), Offset(size.width / 2, size.height), 1.6.dp.toPx()) }
        }
        Spacer(Modifier.width(10.dp))
        Text(if (!idle) "暂停对话" else if (pendingStart) "取消开始说话" else if (connecting) "连接后开始说话" else "开始说话", fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsDialog(vm: OpenNomiCloudViewModel, reduceMotion: Boolean, onMotion: (Boolean) -> Unit, onClose: () -> Unit, onAccount: () -> Unit) {
    val settingsContext = LocalContext.current
    var phoneControl by remember { mutableStateOf(vm.voiceSettings.phoneControl) }
    var continuous by remember { mutableStateOf(vm.voiceSettings.continuousConversation) }
    var realtime by remember { mutableStateOf(vm.voiceSettings.realtimeConversation) }
    var alternateDialog by remember {mutableStateOf(false)}
    var fastResponse by remember {mutableStateOf(vm.voiceSettings.fastResponse)}
    var systemSpeech by remember { mutableStateOf(vm.voiceSettings.systemSpeechFallback) }
    if(alternateDialog) {VoiceEndpointDialog(vm,{alternateDialog=false});return}
    AlertDialog(onDismissRequest = onClose, title = { Text("NOMI 设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingSwitch("语音控制优先", "首页可直接切换聊天或手机控制；控制模式不播放聊天回复", phoneControl) { phoneControl=it;vm.setPhoneControl(it) }
            SettingSwitch("连续对话", "回复后继续听你说话", continuous) { continuous = it; vm.voiceSettings.continuousConversation = it; vm.pauseConversation() }
            SettingSwitch("快速回复", "缩短说完后的等待；句中停顿较长时可关闭", fastResponse) {fastResponse=it;vm.voiceSettings.fastResponse=it;vm.pauseConversation()}
            TextButton(onClick={alternateDialog=true}) {Text("备用语音接口 · ${if(vm.endpointSettings.enabled)"已启用" else "小智默认"}")}
            SettingSwitch("允许打断", "手机支持回声消除时，可开口打断", realtime) { realtime = it; vm.voiceSettings.realtimeConversation = it; vm.pauseConversation() }
            TextButton(onClick = { settingsContext.startActivity(Intent(settingsContext, ai.opennomi.app.voice.FishAudioActivity::class.java)); onClose() }) { Text("FishAudio 独立朗读、音色与申请") }
            SettingSwitch("系统朗读回退", "默认关闭；小智原声失败时才使用，FishAudio 失败保留文字", systemSpeech) { systemSpeech=it;vm.voiceSettings.systemSpeechFallback=it }
            SettingSwitch("减少动效", "保留表情，关闭循环动画", reduceMotion, onMotion)
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Secondary.copy(alpha = .2f))
            TextButton(onClick = onAccount) { Text("设备绑定与账号") }
            Text("设备：${vm.deviceId()}", fontSize = 11.sp, color = Secondary)
            TextButton(onClick = { vm.disconnect(); vm.connect(); onClose() }) { Text("重新连接") }
            Text("OpenNomi 0.49 · 独立手机语音控制", fontSize = 12.sp, color = Secondary)
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("完成") } })
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) { Text(title, fontSize = 15.sp); Text(detail, fontSize = 12.sp, color = Secondary) }
        Switch(checked, onChecked)
    }
}

@Composable
private fun AccountDialog(onClose: () -> Unit, onBound: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(12.dp), shape = RoundedCornerShape(22.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("设备绑定", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    TextButton(onClick = onClose) { Text("关闭") }
                    TextButton(onClick = onBound) { Text("已绑定，重连") }
                }
                AndroidView(factory = { XiaozhiAccountView(it) }, onRelease = { it.dispose() }, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
