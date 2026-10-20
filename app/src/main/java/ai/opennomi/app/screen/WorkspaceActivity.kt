package ai.opennomi.app.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import android.view.WindowManager
import java.io.File
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import ai.opennomi.app.ui.EmotionBallView

class WorkspaceActivity:ComponentActivity() {
    private var tab=mutableStateOf("vision");private var refresh=mutableIntStateOf(0);private var pendingVideo=false;private var pendingOverlay=false
    private var translationMode=mutableStateOf("video")
    private val capture=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->
        if(r.resultCode==RESULT_OK && r.data!=null){
            runCatching{ContextCompat.startForegroundService(this,Intent(this,ScreenShareService::class.java).setAction(ScreenShareService.START).putExtra("grant",r.data))}
                .onSuccess{lifecycleScope.launch{withTimeoutOrNull(5000){while(!ScreenState.state.value.active)delay(100)};if(ScreenState.state.value.active){if(pendingVideo){pendingVideo=false;activateAudio()}else moveTaskToBack(true)}else{pendingVideo=false;ScreenState.event("共享准备失败，请重试")}}}
                .onFailure{ScreenState.event("系统未能启动共享，请重新授权并检查应用权限")}
        }else {pendingVideo=false;ScreenState.event("未授权共享，当前没有读取屏幕")}
    }
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()){launchShareGrant()}
    private val microphone=registerForActivityResult(ActivityResultContracts.RequestPermission()){ok->if(ok)requestVideo() else ScreenState.event("未允许音频权限，仍可翻译屏幕文字")}
    private val subtitleOverlay=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){
        if(pendingOverlay){pendingOverlay=false;if(Settings.canDrawOverlays(this))requestVideo()
        else ScreenState.event("需要允许悬浮窗，中文字幕才能显示在视频上方；允许后再开启视频字幕")}
    }
    private val handsFreePermissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){grants->
        if(grants[Manifest.permission.RECORD_AUDIO]==true) {
            (application as ai.opennomi.app.NomiApplication).cloudModel.voiceSettings.phoneControl=true
            ai.opennomi.app.voice.NomiVoiceService.start(this,ScreenState.state.value.active)
            ScreenState.event(if(grants[Manifest.permission.CAMERA]==true)"语音和相机权限已允许，可直接说打开手电筒或打开相机。" else "语音已开启；相机权限未允许，手电筒和拍照暂不可用。")
        } else ScreenState.event("未允许麦克风权限，无法接收语音指令。")
        refresh.intValue++
    }
    private val image=registerForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)import(uri,"")}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);ScreenAssistant.init(this);tab.value=intent.getStringExtra("tab") ?: "vision";readTranslationMode(intent);handleShare(intent);setContent{Workspace()}}
    override fun onResume(){super.onResume();refresh.intValue++;ScreenShareService.instance?.refreshOverlay();if(pendingOverlay && Settings.canDrawOverlays(this)){pendingOverlay=false;requestVideo()}}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);intent.getStringExtra("tab")?.let{tab.value=it};readTranslationMode(intent);handleShare(intent)}
    private fun readTranslationMode(intent:Intent){intent.getStringExtra("translation-mode")?.let{translationMode.value=if(it in setOf("video","text","screen","history"))it else "video"}}
    private fun handleShare(intent:Intent){if(intent.action!=Intent.ACTION_SEND)return;val text=intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty();if(intent.type?.startsWith("image/")==true){val uri=if(android.os.Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java) else  @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri);if(uri!=null)import(uri,text)}else if(text.isNotBlank()){MemoryStore(this).use{it.add(if(Regex("https?://").containsMatchIn(text))"link" else "text",text,"系统分享")};tab.value="fragments";refresh.intValue++};intent.action=null}
    private fun import(uri:Uri,text:String){lifecycleScope.launch{try{withContext(Dispatchers.IO){MemoryStore(this@WorkspaceActivity).use{it.importImage(uri,text)}};tab.value="fragments";refresh.intValue++;ScreenState.event("已保存原图，图片字节保持原样")}catch(t:Throwable){ScreenState.event(t.message ?: "图片导入失败")}}}
    private fun startShare(){
        if(android.os.Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else launchShareGrant()
    }
    private fun launchShareGrant(){
        val mgr=getSystemService(MediaProjectionManager::class.java)
        val request=if(android.os.Build.VERSION.SDK_INT>=34)mgr.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())else mgr.createScreenCaptureIntent()
        capture.launch(request)
    }
    private fun activateAudio() {
        val service=ScreenShareService.instance ?: run {pendingVideo=true;startShare();return}
        service.toggleAudio()
        if(ScreenState.state.value.audio)moveTaskToBack(true)
    }
    private fun requestVideo() {
        if(ScreenState.state.value.audio){ScreenShareService.instance?.toggleAudio();return}
        if(!Settings.canDrawOverlays(this)) {
            pendingOverlay=true
            ScreenState.event("请允许悬浮窗，用于显示视频中文字幕")
            subtitleOverlay.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            return
        }
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){microphone.launch(Manifest.permission.RECORD_AUDIO);return}
        activateAudio()
    }
    private fun confirmStep(){moveTaskToBack(true);Handler(Looper.getMainLooper()).postDelayed({ScreenAssistant.confirm()},650)}
    @Composable private fun Workspace(){
        val state by ScreenState.state.collectAsStateWithLifecycle()
        val cfg=remember{ScreenAssistant.settings()}
        val contentScroll=rememberScrollState()
        LaunchedEffect(tab.value,translationMode.value){contentScroll.scrollTo(0)}
        MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF7DD3FC),secondary=Color(0xFFC084FC),background=Color(0xFF070F19),surface=Color(0xFF142131),onSurface=Color(0xFFF0F5FC))){
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
                Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){TextButton(onClick={finish()}){Text("‹")};Column(Modifier.weight(1f)){Text("OpenNomi",fontSize=24.sp,fontWeight=FontWeight.SemiBold);Text(if(state.active)"● 屏幕共享中" else "屏幕互动工作台 · ${ai.opennomi.app.BuildConfig.VERSION_NAME}",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)};if(state.active)TextButton(onClick={ScreenShareService.instance?.stopSelf()}){Text("停止",color=Color(0xFFF87171))}}
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    listOf("vision" to "看屏幕","tasks" to "Agent","translation" to "翻译","fragments" to "碎片本","memory" to "记忆","settings" to "连接","help" to "申请指南").forEach{(key,label)->FilterChip(selected=tab.value==key,onClick={tab.value=key},label={Text(label)})}
                }
                Column(Modifier.weight(1f).verticalScroll(contentScroll).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                    CardBlock("状态"){Text(state.status,fontSize=14.sp);if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))}
                    when(tab.value){"vision"->Vision(state);"tasks"->Tasks(state);"translation"->Translation(state);"fragments"->Fragments();"memory"->Memories();"help"->Help();else->Connection(cfg)}
                }
            }}
        }
    }
    @Composable private fun CardBlock(title:String,content:@Composable ColumnScope.()->Unit){Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,fontSize=17.sp,fontWeight=FontWeight.SemiBold);content()}}}
    @Composable private fun WideButton(label:String,onClick:()->Unit){Button(onClick=onClick,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(16.dp)){Text(label)}}
    @Composable private fun Vision(state:WorkspaceState){
        CardBlock("我在这里，陪你看屏幕"){
            AndroidView(factory={EmotionBallView(it)},onRelease={it.dispose()},update={it.setMood(if(state.busy)"thinking" else "happy");it.setMotionEnabled(true);it.resumeAnimation()},modifier=Modifier.fillMaxWidth().height(180.dp))
            Text("开启后切到其他 App，球球会留在屏幕上。点球球问这页，双击保存文字，长按输入问题；上滑翻译、右滑语音、下滑历史。",fontSize=14.sp)
            if(!state.active)WideButton("开启屏幕共享"){startShare()}else WideButton("回到正在看的 App"){moveTaskToBack(true)}
            Text("共享内容先在本机识别。问视觉模型或开启翻译时，所需页面内容会发送到你填写的服务；系统保护的页面可能显示黑屏。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CardBlock("视觉模型") {
            val verified = ScreenAssistant.settings().visionVerified()
            Text(if(verified) "图片连接测试已通过" else "需要连接视觉模型，才能理解画面中的图片")
            WideButton(if(verified) "查看模型连接" else "连接免费视觉模型") { tab.value="settings" }
            Text("离线文字读取可以直接使用；模型看图需要联网和你的服务密钥。",fontSize=12.sp)
        }
        Readiness(state)
        Permissions()
        CardBlock("问问当前页面") {
            var question by remember { mutableStateOf("") }
            OutlinedTextField(question,{question=it},label={Text("想问这页什么？")},modifier=Modifier.fillMaxWidth())
            WideButton("回到目标 App 后提问") {
                if(!state.active) ScreenState.event("请先开启屏幕共享")
                else if(state.busy) ScreenState.event("正在处理上一条请求")
                else {moveTaskToBack(true);ScreenAssistant.askAfterReturning(question.ifBlank{"解释当前页面，告诉我重点"})}
            }
        }
        CardBlock("当前页面 · ${state.page.app.ifBlank{"等待外部页面"}}"){
            Text(state.page.text.ifBlank{"切换到要看的页面后，屏幕文字会出现在这里。"},fontSize=14.sp)
            WideButton("保存页面文字"){ScreenAssistant.savePage();refresh.intValue++}
        }
        if(state.reply.isNotBlank())CardBlock("NOMI 的回复"){Text(state.reply);TextButton(onClick={MemoryStore(this@WorkspaceActivity).use{it.add("insight",state.reply,"屏幕问答")};refresh.intValue++}){Text("保存这段回复")}}
    }
    @Composable private fun Permissions(){
        val tick=refresh.intValue
        CardBlock("手机权限"){
            WideButton("允许麦克风和相机，开启免手操作") { handsFreePermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA)) }
            Text("操作结果直接显示，跳过播报；下一句可以继续控制。可以说打开相机、拍照、退出相机，或打开手电筒、关掉手电筒。",fontSize=12.sp)
            Text("悬浮球：${if(Settings.canDrawOverlays(this@WorkspaceActivity))"已允许" else "未允许"} · 页面控件：${if(ScreenAccessService.instance!=null)"已连接" else "未连接"}",fontSize=13.sp)
            TextButton(onClick={startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}){Text("允许悬浮窗")}
            TextButton(onClick={startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("开启 OpenNomi 无障碍读取")}
            Text("Android 13 若提示受限设置，可在系统应用信息右上角允许受限设置，再回来开启。",fontSize=12.sp)
        }
    }
    @Composable private fun Tasks(state:WorkspaceState){
        var task by remember{mutableStateOf(state.task)};var typed by remember{mutableStateOf("")}
        CardBlock("Agent · 当前任务"){
            OutlinedTextField(task,{task=it},label={Text("你想在当前页面做什么")},modifier=Modifier.fillMaxWidth(),minLines=2)
            WideButton("开始连续执行任务"){
                when {
                    !state.active -> ScreenState.event("请先在看屏幕页开启屏幕共享")
                    ScreenAccessService.instance==null -> ScreenState.event("请先开启 OpenNomi 无障碍服务")
                    !ScreenAssistant.settings().modelReady() -> {tab.value="settings";ScreenState.event("请先连接视觉模型")}
                    task.isBlank() -> ScreenState.event("请填写任务")
                    state.busy -> ScreenState.event("请等待当前请求完成")
                    else -> {
                        if(ContextCompat.checkSelfPermission(this@WorkspaceActivity,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED && !(application as ai.opennomi.app.NomiApplication).cloudModel.backgroundConversation.value)
                            ai.opennomi.app.voice.NomiVoiceService.start(this@WorkspaceActivity,true)
                        moveTaskToBack(true)
                        lifecycleScope.launch{delay(500);(application as ai.opennomi.app.NomiApplication).cloudModel.runAgentTask(task)}
                    }
                }
            }
            Text("普通步骤会连续执行，每步重新看屏幕。可说暂停任务、继续任务或取消任务；发送、支付、提交等需单独说确认执行。一次最多二十步。",fontSize=12.sp)
            state.proposed?.let{step->Text(step.describe(),color=MaterialTheme.colorScheme.primary);WideButton("确认这一步，回到目标 App 执行"){if(state.voiceTaskConfirmation){moveTaskToBack(true);lifecycleScope.launch{delay(500);(application as ai.opennomi.app.NomiApplication).cloudModel.confirmVoiceTask()}}else confirmStep()};TextButton(onClick={(application as ai.opennomi.app.NomiApplication).cloudModel.controlTask("cancel")}){Text("取消任务")}}
            if(state.busy || state.agentRunning)TextButton(onClick={(application as ai.opennomi.app.NomiApplication).cloudModel.controlTask("pause_task")}){Text("暂停任务")}
            if(state.task.isNotBlank() && !state.busy && !state.agentRunning)TextButton(onClick={moveTaskToBack(true);lifecycleScope.launch{delay(500);(application as ai.opennomi.app.NomiApplication).cloudModel.resumeVoiceTask()}}){Text("继续任务")}
        }
        CardBlock("本地控件操作"){
            Text("无模型也能操作已识别的控件。页面变化后，需要重新选择。",fontSize=12.sp)
            state.page.nodes.take(30).forEach{n->Row(verticalAlignment=Alignment.CenterVertically){Text("#${n.id} ${n.text.ifBlank{if(n.editable)"输入框" else "按钮"}}",modifier=Modifier.weight(1f),fontSize=13.sp);if(n.clickable)TextButton(onClick={ScreenAssistant.suggestLocal(Step("click",n.id))}){Text("点击")};if(n.editable)TextButton(onClick={ScreenAssistant.suggestLocal(Step("type",n.id,typed))}){Text("填写")}}}
            OutlinedTextField(typed,{typed=it},label={Text("要填写的文字")},modifier=Modifier.fillMaxWidth())
            Row{TextButton(onClick={ScreenAssistant.suggestLocal(Step("back"))}){Text("返回")};TextButton(onClick={ScreenAssistant.suggestLocal(Step("scroll"))}){Text("下滚")}}
        }
        CardBlock("任务记录") {if(state.history.isEmpty())Text("还没有任务记录");state.history.asReversed().take(30).forEach{Text("• $it",fontSize=13.sp)};val rows=remember(refresh.intValue,state.history.size){MemoryStore(this@WorkspaceActivity).use{it.list("task")}};rows.take(20).forEach{Text("已操作 · ${it.text}",fontSize=12.sp)}}
    }
    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun Translation(state:WorkspaceState) {
        val cfg=remember{ScreenAssistant.settings()}
        val scope=rememberCoroutineScope()
        var phone by remember{mutableStateOf(cfg.phoneTranslation)}
        var source by remember{mutableStateOf(cfg.translationSource)}
        var cellular by remember{mutableStateOf(cfg.translationCellular)}
        var downloadOptions by remember{mutableStateOf(false)}
        var audioModel by remember{mutableStateOf(cfg.playbackModel)}
        var showOriginal by remember{mutableStateOf(cfg.subtitleOriginal)}
        var timing by remember{mutableStateOf(cfg.subtitleTiming)}
        var videoMessage by remember{mutableStateOf("")}
        var videoPreparing by remember{mutableStateOf(false)}
        var input by remember{mutableStateOf("Hello, welcome to my channel.")}
        var output by remember{mutableStateOf("")}
        var busy by remember{mutableStateOf(false)}
        val resultPosition=remember{BringIntoViewRequester()}
        val keyboard=LocalSoftwareKeyboardController.current
        val focus=LocalFocusManager.current
        var running by remember{mutableStateOf<Job?>(null)}
        var videoOptions by remember{mutableStateOf(false)}
        var mode by translationMode
        LaunchedEffect(mode,output,busy) {
            if(mode=="text" && output.isNotBlank() && !busy) {
                withFrameNanos { }
                resultPosition.bringIntoView()
            }
        }
        val progress by ScreenAssistant.phoneTranslator.status.collectAsStateWithLifecycle()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("video" to "视频字幕","text" to "文字翻译","screen" to "画面文字","history" to "近期译文").forEach{(key,label)->FilterChip(selected=mode==key,onClick={mode=key},label={Text(label)},enabled=!busy)}
        }
        if(mode=="video")CardBlock("无字幕视频 · 实时中文字幕") {
            Text("直接听视频原声，不需要视频自带字幕。先选原声语言，准备好模型后再播放。",fontSize=14.sp)
            WideButton(if(state.audio)"暂停视频字幕" else if(state.active && state.captionHistory.isNotEmpty())"继续视频字幕" else "开启无字幕视频字幕") {
                if(busy)ScreenState.event("请先等待或取消模型准备") else requestVideo()
            }
            if(state.audio) {
                Text(state.status,color=MaterialTheme.colorScheme.primary)
                TextButton(onClick={ScreenShareService.instance?.showBackgroundSubtitles();moveTaskToBack(true)}){Text("后台显示悬浮字幕")}
                Text("切到播放器或其他应用后，译文会继续显示在浮层。点悬浮球可暂停、继续、改位置或字号；通知栏也能控制。",fontSize=12.sp)
                Text("待完成翻译 ${state.audioQueued} 段 · 等待显示 ${state.captionQueued} 条",fontSize=12.sp)
                Text("快显模式优先跟随播放，后续译文不等长句读完才翻译；完整语句模式按阅读时长逐条显示。近期译文保留完整结果。",fontSize=12.sp)
                TextButton(onClick={ScreenShareService.instance?.resyncAudio()}){Text("重新同步当前声音")}
                Text("重新同步会跳过积压语句，已完成的近期译文保留。",fontSize=12.sp)
            }
            TextButton(onClick={mode="history"}){Text("查看近期视频译文（${state.captionHistory.size}）")}
            if(state.notice.isNotBlank())Text(state.notice,color=MaterialTheme.colorScheme.error)
            if(state.caption.isNotBlank()) {
                SelectionContainer{Text(state.caption)}
                if(showOriginal && state.captionOriginal.isNotBlank())Text("原文：${state.captionOriginal}",fontSize=12.sp)
                if(phone)Text("由 Google 翻译",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(state.audioPreview.isNotBlank())Text("识别中：${state.audioPreview}",fontSize=12.sp)
            Row(verticalAlignment=Alignment.CenterVertically){Text("显示识别原文，方便核对",Modifier.weight(1f),fontSize=13.sp);Switch(showOriginal,{showOriginal=it;cfg.subtitleOriginal=it;ScreenShareService.instance?.refreshOverlay()})}
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                SubtitlePosition.entries.forEach{position->FilterChip(selected=cfg.subtitlePosition==position.name,onClick={cfg.subtitlePosition=position.name;refresh.intValue++;ScreenShareService.instance?.refreshOverlay()},label={Text("字幕${position.label}")} )}
            }
            if(state.audio)TextButton(onClick={videoOptions=!videoOptions}){Text(if(videoOptions)"收起语言与模型设置" else "展开语言与模型设置")}
            if(!state.audio || videoOptions) {
            Row(verticalAlignment=Alignment.CenterVertically){Text("手机翻译 · 无需 API Key",Modifier.weight(1f),fontSize=13.sp);Switch(phone,{phone=it;cfg.phoneTranslation=it},enabled=!state.audio && !busy)}
            if(!phone)TextButton(onClick={tab.value="settings"}){Text("配置在线翻译服务")}
            if(phone)Text("手机离线翻译适合简单句。原文正确但译文生硬或意思有误时，可切换已配置的在线翻译；日语等语言译成中文会经英语中转，质量可能受影响。",fontSize=12.sp)
            Text("原声语言 · 左右滑动选择",fontSize=12.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                PlaybackModels.choices.forEach { model ->
                    FilterChip(selected=audioModel==model.id,onClick={audioModel=model.id;cfg.playbackModel=model.id;refresh.intValue++},label={Text(model.label)},enabled=!state.audio && !busy)
                }
            }
            val selected=PlaybackModels.selected(audioModel)
            Text(if(PlaybackModels.ready(this@WorkspaceActivity,selected))"${selected.label}语音模型已准备好" else if(selected.id=="en")"英语模型已内置，首次会安装到本机" else "${selected.label}语音模型首次下载约 ${selected.sizeMb} MB，之后可离线识别",fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                SubtitleTiming.entries.forEach{mode->FilterChip(selected=timing==mode.name,onClick={timing=mode.name;cfg.subtitleTiming=mode.name},label={Text(mode.label)},enabled=!state.audio)}
            }
            Text(if(timing==SubtitleTiming.FAST.name)"连续讲话约 3.5 秒后优先等短停顿分段，不停顿时最多额外等 1.2 秒。快显优先当前进度，未及显示的结果可在近期译文回看。" else "约 5.5 秒后优先等短停顿分段，不停顿时最多额外等 1.2 秒，保留更长上下文。浮层按阅读时长逐条显示，可能落后于播放。",fontSize=12.sp)
            TextButton(onClick={busy=true;videoPreparing=true;videoMessage="正在准备视频字幕模型";running=scope.launch {
                try {
                    val preparation=currentCoroutineContext()[Job]!!
                    withContext(Dispatchers.IO){PlaybackModels.prepare(this@WorkspaceActivity,selected,cfg.translationCellular,{preparation.isActive},{message->scope.launch{if(preparation.isActive)videoMessage=message}})}
                    if(cfg.phoneTranslation)ScreenAssistant.phoneTranslator.prepare(selected.language,cfg.translationCellular)
                    videoMessage="${selected.label}语音和翻译模型已就绪，可以播放视频"
                }catch(e:CancellationException){throw e}
                catch(e:Exception){videoMessage=e.message ?: "视频字幕模型准备失败"}
                finally{busy=false;videoPreparing=false;running=null}
            }},enabled=!busy && !state.audio){Text("提前准备视频字幕模型")}
            if(videoPreparing){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={running?.cancel();videoMessage="已取消模型准备，已安装模型保留"}){Text("取消视频模型准备")}}
            if(videoMessage.isNotBlank())Text(videoMessage,fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
            Row(verticalAlignment=Alignment.CenterVertically){Text("允许移动网络下载模型",Modifier.weight(1f),fontSize=13.sp);Switch(cellular,{cellular=it;cfg.translationCellular=it},enabled=!busy && !state.audio)}
            Text("默认只在 Wi-Fi 下载。原文会先显示；积压时合并相邻语句翻译以追赶进度。英语可选增强模型（首次约 128 MB），应对照原文比较效果；模型更大并不保证每句更准或更快。口音、音乐和重叠讲话会影响识别。",fontSize=12.sp)
            Text("播放应用必须允许系统捕获声音；受限视频无法强行读取。手机翻译在本机处理；选择连接页的在线模型或 LibreTranslate 时，会发送识别文字。",fontSize=12.sp)
            }
        }
        if(mode=="history")CardBlock("本次共享 · 近期视频译文") {
            Text("保留最近 20 条原文和译文，只在本次共享中暂存。停止共享后清空；暂停视频字幕后仍可回看。",fontSize=12.sp)
            if(state.audio)TextButton(onClick={ScreenShareService.instance?.toggleAudio()}){Text("暂停视频字幕，回看译文")}
            else if(state.active)WideButton("继续视频字幕"){requestVideo()}
            if(state.captionHistory.isEmpty())Text("还没有视频译文。开启视频字幕并播放后会出现在这里。")
            state.captionHistory.asReversed().forEach{caption->
                Text(caption.language,fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
                SelectionContainer{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(caption.translated);Text("原文：${caption.original}",fontSize=13.sp)}}
                if(caption.confidence==null || caption.confidence<.55)Text("识别中有不确定词，请核对原文",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
                HorizontalDivider()
            }
            if(state.captionHistory.isNotEmpty())TextButton(onClick={ScreenState.update{it.copy(captionHistory=emptyList())}}){Text("清空本次近期译文")}
        }
        if(mode=="text")CardBlock("文字翻译 · 译成中文") {
            Row(verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text("手机翻译 · 无需 API Key");Text("首次下载语言模型后可离线使用",fontSize=12.sp)}
                Switch(phone,{phone=it;cfg.phoneTranslation=it;output=""},enabled=!busy && !state.audio)
            }
            if(phone) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "自动","en" to "英语","ja" to "日语","ko" to "韩语","fr" to "法语","de" to "德语","ru" to "俄语").forEach{(code,label)->
                        FilterChip(selected=source==code,onClick={source=code;cfg.translationSource=code;output=""},label={Text(label)},enabled=!busy && !state.audio)
                    }
                }
                Text(progress,fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
            } else {
                Text("当前使用连接页保存的模型或 LibreTranslate 服务。",fontSize=13.sp)
                TextButton(onClick={tab.value="settings"}){Text("配置在线翻译服务")}
            }
            OutlinedTextField(input,{input=it.take(1400)},label={Text("粘贴或输入原文")},modifier=Modifier.fillMaxWidth(),minLines=2,enabled=!busy)
            Button(onClick={focus.clearFocus();keyboard?.hide();busy=true;output="";running=scope.launch {
                try {output=ScreenAssistant.translate(input)}
                catch(e:CancellationException){throw e}
                catch(e:Exception){output=e.message ?: "翻译未完成"}
                finally{busy=false;running=null}
            }},enabled=!busy && !state.audio && input.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(if(busy)"正在处理…" else "翻译为中文")}
            if(state.audio)Text("视频字幕运行中；请先在视频字幕页停止，再进行文字翻译或更改语言设置。",fontSize=12.sp)
            if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={running?.cancel();output="已取消本次翻译"}){Text("取消")}}
            if(output.isNotBlank())Column(Modifier.fillMaxWidth().bringIntoViewRequester(resultPosition)) {
                Text("译文",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
                SelectionContainer {Text(output)}
                if(phone)Text("由 Google 翻译",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(phone) {
                TextButton(onClick={downloadOptions=!downloadOptions},enabled=!busy){Text(if(downloadOptions)"收起模型与下载设置" else "模型与下载设置")}
                if(downloadOptions) {
                Row(verticalAlignment=Alignment.CenterVertically){Text("允许移动网络下载模型",Modifier.weight(1f),fontSize=13.sp);Switch(cellular,{cellular=it;cfg.translationCellular=it},enabled=!busy && !state.audio)}
                Text("默认只在 Wi-Fi 下载。每种语言模型约 30 MB；模型服务不可达时会显示原因。文字在本机翻译；SDK 会发送运行统计数据。",fontSize=12.sp)
                TextButton(onClick={busy=true;output="";running=scope.launch {
                    try {ScreenAssistant.phoneTranslator.prepare(if(source=="auto")"en" else source,cellular);output="语言模型已准备好，可以翻译"}
                    catch(e:CancellationException){throw e}
                    catch(e:Exception){output=e.message ?: "模型准备未完成"}
                    finally{busy=false;running=null}
                }},enabled=!busy && !state.audio){Text(if(source=="auto")"准备英语模型" else "准备所选语言模型")}
                }
            }
        }
        if(mode=="screen")CardBlock("画面文字 · 底部浮条") {
            Text("用于网页、画面里的外语文字和已有字幕；无字幕视频请使用上方视频声音模式。",fontSize=13.sp)
            WideButton(if(state.translation)"暂停实时翻译" else "开启画面文字翻译") {
                val service=ScreenShareService.instance
                if(service==null)ScreenState.event("请先在看屏幕页开启共享")
                else {service.toggleTranslation();if(ScreenState.state.value.translation)moveTaskToBack(true)}
            }
        }
        if(mode!="video" && mode!="history")CardBlock("最近译文") {val rows=remember(refresh.intValue,state.caption){MemoryStore(this@WorkspaceActivity).use{it.list("translation")}};if(rows.isEmpty())Text("尚无译文");rows.take(10).forEach{Text(it.text,fontSize=13.sp);HorizontalDivider()}}
    }
    @Composable private fun Fragments(){
        var filter by remember{mutableStateOf("")};var note by remember{mutableStateOf("")};var search by remember{mutableStateOf("")}
        CardBlock("碎片本"){
            Text("在其他 App 分享图片到 OpenNomi，或在这里选图，会保存原始文件。悬浮球双击保存的是页面文字。",fontSize=13.sp)
            WideButton("添加原图"){image.launch("image/*")}
            OutlinedTextField(note,{note=it},label={Text("记录一个想法")},modifier=Modifier.fillMaxWidth(),minLines=2)
            TextButton(onClick={if(note.isNotBlank()){MemoryStore(this@WorkspaceActivity).use{it.add(if(Regex("https?://").containsMatchIn(note))"link" else "text",note)};note="";refresh.intValue++}}){Text("保存想法")}
            OutlinedTextField(search,{search=it},label={Text("搜索标题、内容或标签")},modifier=Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("" to "全部","image" to "图片","text" to "文字","link" to "链接","insight" to "问答").forEach{(k,n)->FilterChip(filter==k,{filter=k},label={Text(n)})}}
        }
        val rows=remember(refresh.intValue,filter,search){MemoryStore(this@WorkspaceActivity).use{it.list(filter)}.filter{it.kind in setOf("image","text","link","insight") && listOf(it.title,it.text,it.tags,it.summary).any{v->v.contains(search,true)}}}
        if(rows.isEmpty())CardBlock("从一张喜欢的图片开始"){Text("这里不会预填示例图片或虚构记录。你保存的内容才会出现在这里。")}
        rows.forEach{item->CardBlock(item.title.ifBlank{item.source}){
            if(item.image.isNotBlank())AndroidView(factory={ImageView(it).apply{scaleType=ImageView.ScaleType.CENTER_CROP}},update={view->val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(item.image,opts);opts.inSampleSize=maxOf(1,opts.outWidth/800,opts.outHeight/800);opts.inJustDecodeBounds=false;view.setImageBitmap(BitmapFactory.decodeFile(item.image,opts))},modifier=Modifier.fillMaxWidth().height(200.dp))
            Text(item.text,fontSize=14.sp)
            if(item.summary.isNotBlank())Text("AI 已整理：${item.summary}",fontSize=13.sp)
            if(item.tags.isNotBlank())Text(item.tags,color=MaterialTheme.colorScheme.primary,fontSize=12.sp)
            SavedItemEditor(item)
            if(item.image.isNotBlank()) PictureQuestion(item)
            TextButton(onClick={MemoryStore(this@WorkspaceActivity).use{it.delete(item)};refresh.intValue++}){Text("删除",color=Color(0xFFF87171))}
        }}
    }
    @Composable private fun PictureQuestion(item:SavedItem) {
        var question by remember(item.id) {mutableStateOf("")}
        var reply by remember(item.id) {mutableStateOf("")}
        var loading by remember(item.id) {mutableStateOf(false)}
        var succeeded by remember(item.id) {mutableStateOf(false)}
        val scope=rememberCoroutineScope()
        OutlinedTextField(question,{question=it},label={Text("问这张图 · 可留空概括")},modifier=Modifier.fillMaxWidth())
        Button(onClick={
            if(!ScreenAssistant.settings().modelReady()){tab.value="settings";ScreenState.event("请先连接视觉模型")}
            else {loading=true;reply="";succeeded=false;scope.launch {
                try {reply=PictureAssistant.ask(File(item.image),question.ifBlank{"用中文概括这张图片，说明重点"});succeeded=true}
                catch(e:CancellationException){throw e}
                catch(t:Throwable){reply=t.message ?: "看图请求未完成"}
                finally {loading=false}
            }}
        },enabled=!loading,modifier=Modifier.fillMaxWidth()){Text(if(loading) "正在看图…" else "让 NOMI 看这张图")}
        Text("仅发送这张图的缩小副本，无需开启屏幕共享。",fontSize=12.sp)
        if(reply.isNotBlank())Text(reply)
        if(succeeded)TextButton(onClick={MemoryStore(this@WorkspaceActivity).use{it.add("insight",reply,"图片问答")};refresh.intValue++}){Text("保存回答")}
    }
    @Composable private fun Memories(){
        var note by remember{mutableStateOf("")};var category by remember{mutableStateOf("项目")};var filter by remember{mutableStateOf("")}
        CardBlock("我的记忆"){
            Text("记忆存放在本机，可编辑分类或删除。开启连接页的记忆使用后，屏幕问答会引用最近记忆。",fontSize=14.sp)
            OutlinedTextField(note,{note=it},label={Text("希望记住的事")},minLines=3,modifier=Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState())){listOf("项目","兴趣","生活").forEach{c->FilterChip(category==c,{category=c},label={Text(c)})}}
            WideButton("记住这件事"){if(note.isNotBlank()){MemoryStore(this@WorkspaceActivity).use{it.add("memory",note,category=category)};note="";refresh.intValue++}}
            Row(Modifier.horizontalScroll(rememberScrollState())){listOf("","项目","兴趣","生活").forEach{c->FilterChip(filter==c,{filter=c},label={Text(c.ifBlank{"近期"})})}}
        }
        val rows=remember(refresh.intValue,filter){MemoryStore(this@WorkspaceActivity).use{it.list("memory")}.filter{filter.isBlank() || it.category==filter}}
        rows.forEach{item->CardBlock(item.title.ifBlank{"记忆 · ${item.category.ifBlank{"未分类"}}"}){Text(item.text);SavedItemEditor(item);TextButton(onClick={MemoryStore(this@WorkspaceActivity).use{it.delete(item)};refresh.intValue++}){Text("删除")}}}
    }
    @Composable private fun SavedItemEditor(item:SavedItem) {
        var editing by remember(item.id){mutableStateOf(false)}
        var body by remember(item.id,item.text){mutableStateOf(item.text)}
        var title by remember(item.id,item.title){mutableStateOf(item.title)}
        var tags by remember(item.id,item.tags){mutableStateOf(item.tags)}
        var category by remember(item.id,item.category){mutableStateOf(item.category)}
        var organizing by remember(item.id){mutableStateOf(false)}
        val scope=rememberCoroutineScope()
        Row {
            TextButton(onClick={editing=!editing}){Text(if(editing)"收起编辑" else "编辑")}
            TextButton(enabled=!organizing,onClick={organizing=true;scope.launch {
                try {
                    val result=FragmentOrganizer.organize(item)
                    MemoryStore(this@WorkspaceActivity).use{it.edit(item,title=result.title,category=result.category,tags=result.tags,summary=result.summary)}
                    refresh.intValue++;ScreenState.event("这条记录已整理，原图保留")
                } catch(e:CancellationException){throw e}
                catch(e:Exception){ScreenState.event("整理未完成：${e.message}")}
                finally{organizing=false}
            }}){Text(if(organizing)"正在整理…" else "AI 整理")}
        }
        if(editing) {
            OutlinedTextField(title,{title=it},label={Text("标题")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(body,{body=it},label={Text("内容")},modifier=Modifier.fillMaxWidth(),minLines=2)
            OutlinedTextField(category,{category=it},label={Text("分类 · 项目 / 兴趣 / 生活")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(tags,{tags=it},label={Text("标签")},modifier=Modifier.fillMaxWidth())
            TextButton(onClick={MemoryStore(this@WorkspaceActivity).use{it.edit(item,text=body,title=title,category=category,tags=tags)};editing=false;refresh.intValue++}){Text("保存修改")}
        }
    }
    private fun openOfficial(url:String) {
        runCatching {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure {ScreenState.event("无法打开浏览器，请通过申请指南中的官方地址访问")}
    }
    @Composable private fun Help() {
        CardBlock("手机申请与配置指南") {
            Text("先申请一个智谱密钥用于视觉、Agent 和翻译；原声 NOMI 和 AIRI 的配置分别完成。以下指引可以离线查看。")
            WideButton("去填写视觉密钥"){tab.value="settings"}
            Text("账号申请在官方平台完成；密钥只填在服务设置中。",fontSize=12.sp)
        }
        SetupGuide.topics.forEach{topic->CardBlock(topic.title){
            topic.steps.forEachIndexed{index,line->Text("${index+1}. $line",fontSize=14.sp)}
            topic.links.forEach{link->TextButton(onClick={openOfficial(link.url)}){Text(link.label)}}
        }}
    }
    @Composable private fun Readiness(state:WorkspaceState) {
        val config=ScreenAssistant.settings()
        val modelConfigured=config.modelReady()
        val original=(application as ai.opennomi.app.NomiApplication).cloudModel
        val connected by original.connected.collectAsStateWithLifecycle()
        val pairing by original.pairingCode.collectAsStateWithLifecycle()
        CardBlock("现在还缺哪一步") {
            Text("视觉服务：${if(!modelConfigured) "未填写" else if(config.visionVerified()) "图片测试通过" else "已填写，尚未通过图片测试"}")
            if(runCatching{VisionApi.endpoint(config.base).host}.getOrNull()=="open.bigmodel.cn" && config.apiKey.isBlank())Text("智谱 API Key：尚未填写",color=MaterialTheme.colorScheme.error)
            Text("NOMI 原声：${if(pairing!=null) "需要在小智后台绑定设备" else if(connected) "已连接" else "尚未连接"}")
            Text("屏幕共享：${if(state.active) "已开启" else "未开启"} · 无障碍：${if(ScreenAccessService.instance!=null) "已连接" else "未开启"}")
            Text("翻译来源：${if(config.phoneTranslation) "手机翻译（Google）" else if(config.libre.isNotBlank()) "LibreTranslate（优先）" else if(modelConfigured) "已保存的模型" else "尚未配置翻译服务"}")
            Text("连接设置和图片测试不会替你开启系统权限。Agent 需要共享和无障碍；翻译浮条需要共享。",fontSize=12.sp)
            TextButton(onClick={tab.value="help"}){Text("看申请与开启步骤")}
        }
    }
    @Composable private fun Connection(cfg:WorkspaceSettings){
        var base by remember{mutableStateOf(cfg.base)};var model by remember{mutableStateOf(cfg.model)};var secret by remember{mutableStateOf("")};var libre by remember{mutableStateOf(cfg.libre)};var libreSecret by remember{mutableStateOf("")};var includeImage by remember{mutableStateOf(cfg.includeImage)};var autoglm by remember{mutableStateOf(cfg.autoGlm)}
        var translationModel by remember{mutableStateOf(cfg.translationModel)}
        var testing by remember{mutableStateOf(false)}
        var translating by remember{mutableStateOf(false)}
        var translationResult by remember{mutableStateOf("")}
        var result by remember{mutableStateOf(if(cfg.visionVerified()) "上次图片测试通过；修改配置后请重新测试" else "尚未验证视觉连接")}
        val scope=rememberCoroutineScope()
        val locked=testing || translating
        fun save():VisionConnection {
            val endpoint=VisionApi.endpoint(base)
            require(model.isNotBlank()){ "请填写视觉模型名" }
            val previous=runCatching {VisionApi.endpoint(cfg.base)}.getOrNull()
            if((previous==null || previous.host!=endpoint.host || previous.port!=endpoint.port || previous.scheme!=endpoint.scheme) && secret.isBlank())cfg.apiKey=""
            cfg.base=base;cfg.model=model;if(secret.isNotBlank())cfg.apiKey=secret.trim()
            cfg.includeImage=includeImage;cfg.autoGlm=autoglm;cfg.translationModel=translationModel;secret="";cfg.clearVerification()
            return cfg.connection()
        }
        CardBlock("连接视觉模型 · 手机就能完成"){
            Text("推荐 GLM-4.6V-Flash。官方目前列为免费视觉模型，仍需你注册账号、创建 API Key，调用受平台额度和限流规则约束。",fontSize=13.sp)
            Button(onClick={base=VisionApi.FREE_BASE;model=VisionApi.FREE_MODEL;includeImage=true;autoglm=false;result="已填好官方地址和模型名，请继续申请密钥"},enabled=!locked,modifier=Modifier.fillMaxWidth()){Text("1 · 选择 GLM 免费视觉模型")}
            WideButton("2 · 打开官方平台申请 API Key"){
                runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(VisionApi.KEY_PAGE)))}.onFailure{result="无法打开浏览器，请访问 bigmodel.cn，在控制台创建 API Key"}
            }
            Text("登录官方平台 → 创建 API Key → 复制 → 返回这里粘贴。密钥无需发给我。",fontSize=13.sp)
            TextButton(onClick={startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(VisionApi.MODEL_DOC)))}){Text("查看官方模型说明与规则")}
            OutlinedTextField(base,{base=it},label={Text("服务地址 · 基础地址或完整对话地址")},modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(autoCorrect=false,keyboardType=KeyboardType.Uri),enabled=!locked)
            OutlinedTextField(model,{model=it},label={Text("视觉模型名")},modifier=Modifier.fillMaxWidth(),enabled=!locked)
            OutlinedTextField(translationModel,{translationModel=it},label={Text("文字翻译模型 · 留空自动选择")},supportingText={Text("智谱默认 glm-4.7-flash，与视觉共用密钥；其他服务默认使用已有模型")},modifier=Modifier.fillMaxWidth(),enabled=!locked)
            OutlinedTextField(secret,{secret=it},label={Text("3 · 粘贴 API Key")},supportingText={Text("同一服务留空保留已有密钥；换服务时需重新填写")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(autoCorrect=false,keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth(),enabled=!locked)
            Row(verticalAlignment=Alignment.CenterVertically){Text("问屏幕时发送当前画面",Modifier.weight(1f),fontSize=13.sp);Switch(includeImage,{includeImage=it},enabled=!locked)}
            Row(verticalAlignment=Alignment.CenterVertically){Text("AutoGLM 专用动作格式",Modifier.weight(1f),fontSize=13.sp);Switch(autoglm,{autoglm=it},enabled=!locked)}
            Button(onClick={
                runCatching{save()}.onSuccess{connection->testing=true;result="正在发送本机生成的测试图，不发送你的屏幕";scope.launch {
                    try {result=PictureAssistant.test(connection)}
                    catch(e:CancellationException){throw e}
                    catch(t:Throwable){result=t.message ?: "视觉测试未通过"}
                    finally{testing=false}
                }}.onFailure{result=it.message ?: "配置有误"}
            },enabled=!locked,modifier=Modifier.fillMaxWidth()){Text(if(testing) "正在验证图片能力…" else "4 · 保存并测试看图")}
            if(testing)LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(result,color=MaterialTheme.colorScheme.primary)
            TextButton(onClick={runCatching{save()}.onSuccess{result="配置已保存，尚未测试"}.onFailure{result=it.message ?: "配置有误"}},enabled=!locked){Text("仅保存自定义连接")}
            TextButton(onClick={cfg.apiKey="";cfg.clearVerification();secret="";result="密钥已清除"},enabled=!locked){Text("清除密钥")}
            Text("密钥在手机内加密保存。只有模型看图测试成功，才会显示验证通过。测试通过后：看屏幕 → 开启共享，或碎片本 → 添加原图 → 问这张图。",fontSize=12.sp)
        }
        CardBlock("记忆使用") {
            var useMemory by remember { mutableStateOf(cfg.useMemory) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("屏幕问答使用我的记忆", Modifier.weight(1f), fontSize=13.sp); Switch(useMemory, { useMemory=it; cfg.useMemory=it }) }
            Text("开启后，问屏幕时会把最近保存的记忆一并发送给当前 NOMI 或模型服务。", fontSize=12.sp)
        }
        CardBlock("在线翻译服务 · 可选"){
            Text("手机翻译关闭后，优先使用已保存的 LibreTranslate 地址，留空则用模型。托管服务可能需要单独的密钥。",fontSize=13.sp)
            OutlinedTextField(libre,{libre=it},label={Text("LibreTranslate 服务地址")},modifier=Modifier.fillMaxWidth(),enabled=!locked)
            OutlinedTextField(libreSecret,{libreSecret=it},label={Text("LibreTranslate API Key · 可选")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(autoCorrect=false,keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth(),enabled=!locked)
            Button(onClick={
                runCatching {
                    if(libre.isNotBlank()) {
                        val next=VisionApi.endpoint(libre)
                        val previous=runCatching{VisionApi.endpoint(cfg.libre)}.getOrNull()
                        if((previous==null || previous.host!=next.host || previous.port!=next.port || previous.scheme!=next.scheme) && libreSecret.isBlank())cfg.libreKey=""
                    }
                    cfg.libre=libre;if(libreSecret.isNotBlank())cfg.libreKey=libreSecret.trim();libreSecret=""
                }.onSuccess{translationResult="在线翻译地址已保存；在翻译页关闭手机翻译后，可测试此服务"}.onFailure{translationResult=it.message ?: "翻译配置有误"}
            },enabled=!locked,modifier=Modifier.fillMaxWidth()){Text("保存翻译地址")}
            TextButton(onClick={cfg.libre="";cfg.libreKey="";libre="";libreSecret="";translationResult="在线翻译地址已清除；手机翻译开关在翻译页"},enabled=!locked){Text("清除翻译地址和密钥")}
            TextButton(onClick={openOfficial("https://docs.libretranslate.com/guides/api_usage/")}){Text("查看可选服务申请说明")}
        }
        CardBlock("先测试一句翻译") {
            Text("测试当前所选的手机／在线翻译通道，无需开启共享。",fontSize=13.sp)
            Button(onClick={translating=true;translationResult="正在测试已保存的翻译配置";scope.launch {
                try{translationResult="本次译文：\n"+withContext(Dispatchers.IO){ScreenAssistant.translate("Hello, welcome to my channel.")}}
                catch(e:CancellationException){throw e}
                catch(t:Throwable){translationResult=t.message ?: "翻译测试未完成"}
                finally{translating=false}
            }},enabled=!locked,modifier=Modifier.fillMaxWidth()){Text(if(translating) "正在测试…" else "测试已保存配置的文字翻译")}
            if(translationResult.isNotBlank())Text(translationResult)
            TextButton(onClick={tab.value="help"}){Text("需要申请什么？")}
        }
        CardBlock("已接入的组件"){
            Text("Tesseract4Android 4.9.0 · 中英文离线 OCR\nVosk 0.3.75 · 多语言视频原声识别\nOpen-AutoGLM · 动作协议接入\nLibreTranslate · 开源服务接口接入\nML Kit · 手机语言识别与翻译 SDK\n原有 Emotion Ball · 球球动效",fontSize=13.sp)
            Text("语言识别模型随包提供，翻译语言模型首次下载后可离线使用。视觉模型与在线服务仍需单独配置。",fontSize=12.sp)
        }
    }
}
