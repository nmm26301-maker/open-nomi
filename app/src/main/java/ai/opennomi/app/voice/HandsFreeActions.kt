package ai.opennomi.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.accessibilityservice.AccessibilityService
import androidx.core.content.ContextCompat
import ai.opennomi.app.screen.*
import kotlinx.coroutines.*

class HandsFreeActions(private val context: Context) {
    private var torchManager:CameraManager?=null
    private var torchCallback:CameraManager.TorchCallback?=null
    private var torchSwitch:AcknowledgedSwitch?=null
    fun release() { torchCallback?.let { torchManager?.unregisterTorchCallback(it) };torchCallback=null;torchManager=null;torchSwitch=null }
    private data class Pending(val step: Step, val page: Page, val label: String, val expires: Long)
    private var pending: Pending? = null
    fun clear() { pending = null }
    suspend fun execute(command: VoiceCommand): String = executeResult(command).message
    suspend fun executeResult(command: VoiceCommand): ActionResult {
        if(command.action == "cancel") { pending=null; return done("已取消，我继续听你说。") }
        if(command.action=="remember") {pending=null;MemoryStore(context).use{it.add("memory",command.target,"语音记录")};return done("已记住你说的这件事。")}
        if(command.action=="save_page") {
            val state=ScreenState.state.value
            if(!state.active || state.page.sensitive || state.page.text.isBlank())return failed("当前没有可保存的屏幕文字。")
            MemoryStore(context).use{it.add("text",state.page.text,state.page.app+" · 语音保存")}
            return done("已保存页面文字到碎片本。")
        }
        if(command.action == "torch") { pending=null; return torch(command.target == "on") }
        if(command.action == "camera") { pending=null;return camera(command.target) }
        if(command.action == "volume") {
            pending=null
            val audio=context.getSystemService(android.media.AudioManager::class.java)
            val stream=android.media.AudioManager.STREAM_MUSIC
            return try {
                val value=command.target.toIntOrNull()
                if(value != null) {
                    if(value !in 0..100)return failed("音量范围是零到一百。")
                    audio.setStreamVolume(stream,(audio.getStreamMaxVolume(stream)*value/100f).toInt(),android.media.AudioManager.FLAG_SHOW_UI)
                } else {
                    val direction=when(command.target) {"up"->android.media.AudioManager.ADJUST_RAISE;"down"->android.media.AudioManager.ADJUST_LOWER;"mute"->android.media.AudioManager.ADJUST_MUTE;"unmute"->android.media.AudioManager.ADJUST_UNMUTE;else->return failed("没有识别到音量指令。")}
                    audio.adjustStreamVolume(stream,direction,android.media.AudioManager.FLAG_SHOW_UI)
                }
                done("当前媒体音量：${audio.getStreamVolume(stream)}/${audio.getStreamMaxVolume(stream)}。")
            } catch(e:Exception){failed("音量没有调整：${e.message}")}
        }
        if(command.action == "diagnostics") {
            fun granted(permission:String)=ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED
            return done("麦克风${if(granted(Manifest.permission.RECORD_AUDIO))"已允许" else "未允许"}；相机和手电筒${if(granted(Manifest.permission.CAMERA))"已允许" else "未允许"}；无障碍${if(ScreenAccessService.instance!=null)"已连接" else "未连接"}；屏幕共享${if(ScreenState.state.value.active)"已开启" else "未开启"}。控制结果跳过播报，执行后继续收音。")
        }
        val service = ScreenAccessService.instance ?: return failed("请先在系统设置中开启 OpenNomi 无障碍服务，我才能替你操作。")
        if(command.action == "open_app") { pending=null;return openApp(service,command) }
        if(command.action in setOf("home", "exit_app", "back", "notifications")) {
            pending=null
            if(command.action in setOf("home","exit_app","back"))VoiceCamera.close()
            val action=when(command.action) { "home", "exit_app" -> AccessibilityService.GLOBAL_ACTION_HOME; "back" -> AccessibilityService.GLOBAL_ACTION_BACK; else -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS }
            return if(service.performGlobalAction(action)) done("已${when(command.action){"home"->"回到桌面";"exit_app"->"退出当前页面，回到桌面";"back"->"返回";else->"展开通知栏"}}。") else failed("系统没有接受这次操作，请稍后再说一次。")
        }
        if(!ScreenState.state.value.active && !ScreenState.state.value.voiceOn)return failed("请先开启语音控制或屏幕共享，我才能定位页面按钮。")
        val page=service.readPage()
        if(page.sensitive)return failed("当前页面包含密码框，请先切换页面。")
        if(command.action == "confirm") {
            val p=pending ?: return failed("目前没有等待确认的操作。")
            pending=null
            if(SystemClock.elapsedRealtime()>p.expires)return failed("确认已过期，请重新说要执行的操作。")
            return apply(service,p.step,p.page,p.label)
        }
        pending=null
        val step: Step; val label: String
        when(command.action) {
            "scroll" -> { step=Step("scroll",text=command.target);label=if(command.target=="up")"向上滚动" else "向下滚动" }
            "like", "click" -> {
                val nodes=page.nodes.filter { it.clickable && if(command.action=="like")!it.selected && VoiceCommands.likeLabel(it.text) else it.text.trim()==command.target }
                if(nodes.isEmpty())return failed(if(command.action=="like")"没有找到未点赞的按钮，我没有点击。" else "没有找到${command.target}按钮，我没有点击。")
                val ordinal=(command.ordinal ?: if(command.action=="like")command.target.toIntOrNull() else null)?.minus(1)
                if(nodes.size>1 && ordinal==null)return ActionResult(if(command.action=="like")"看到了${nodes.size}个点赞按钮，请说点击第一个点赞按钮，或指定第几个。" else "看到了${nodes.size}个${command.target}按钮，请说点击第一个${command.target}按钮，或指定第几个。",ActionState.CHOICE)
                val node=if(ordinal!=null)nodes.getOrNull(ordinal) ?: return failed("没有找到你指定的第${ordinal+1}个按钮。") else nodes.single()
                step=Step("click",node.id);label=if(command.action=="like")"点击点赞按钮" else "点击${node.text}"
                if(VoiceCommands.sensitive(node.text)) { pending=Pending(step,page,label,SystemClock.elapsedRealtime()+90000); return ActionResult("准备${label}。请在九十秒内说确认执行，或说取消。",ActionState.CONFIRM) }
            }
            "type" -> {
                if(command.target.length>2000)return failed("输入文字超过两千字，请分段输入。")
                val nodes=page.nodes.filter{it.editable}
                if(nodes.size!=1)return failed("请切到只有一个输入框的页面，我才能确定输入位置。")
                step=Step("type",nodes.single().id,command.target);label="输入文字"
            }
            else -> return failed("这条操作暂时不支持。")
        }
        return apply(service,step,page,label)
    }
    private fun done(text:String)=ActionResult(text,ActionState.DONE)
    private fun failed(text:String)=ActionResult(text,ActionState.FAILED)
    suspend fun executePlanned(step:Step,page:Page):ActionResult {
        val service=ScreenAccessService.instance ?: return failed("无障碍服务未连接，任务已停止。")
        if(step.kind=="open_app")return openApp(service,VoiceCommand("open_app",step.text))
        if(page.sensitive)return failed("当前有密码框，任务已停止。")
        val node=page.nodes.firstOrNull{it.id==step.node}
        if(step.kind in setOf("click","type") && node==null)return failed("模型指定的控件不存在，任务已停止。")
        if(step.kind=="click" && (node!!.text.isBlank() || VoiceCommands.sensitive(node.text))) {
            pending=Pending(step,page,step.describe(),SystemClock.elapsedRealtime()+90000)
            return ActionResult("准备${step.describe()}：${node.text.ifBlank{"无名称按钮"}}。请说确认执行，或取消任务。",ActionState.CONFIRM)
        }
        if(step.kind !in setOf("click","type","scroll","back","home"))return failed("模型动作不支持，任务已停止。")
        return apply(service,step,page,step.describe())
    }
    private fun apply(service: ScreenAccessService, step: Step, page: Page, label: String): ActionResult = try {
        if(service.execute(step,page)) done("已${label}。") else failed("${label}未成功，当前控件没有响应。")
    } catch(e:Exception) { failed("没有执行：${e.message}。请重新说一次。") }
    @Suppress("DEPRECATION")
    private suspend fun openApp(service:ScreenAccessService, command:VoiceCommand):ActionResult {
        val pm=context.packageManager
        val entries=pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            .filter { it.activityInfo.exported && it.activityInfo.enabled }
            .map { LauncherTarget(it.loadLabel(pm).toString(),it.activityInfo.packageName) }
        val matches=LauncherTargets.matching(command.target,entries)
        if(matches.isEmpty())return failed("没有找到${command.target}，请说手机上显示的完整应用名称。")
        if(matches.size>1 && command.ordinal==null)return ActionResult("找到了${matches.size}个${command.target}，请说打开第一个${command.target}，或指定第几个。",ActionState.CHOICE)
        val target=matches.getOrNull((command.ordinal ?: 1)-1) ?: return failed("没有找到你指定的应用。")
        val launch=pm.getLaunchIntentForPackage(target.packageName) ?: return failed("这个应用没有可打开的首页。")
        service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val opened=withTimeoutOrNull(4000) {
            while(isActive) { if(service.currentPackage()==target.packageName)return@withTimeoutOrNull true;delay(100) }
            false
        }==true
        return if(opened)done("已打开${target.label}。") else failed("还没有确认打开${target.label}，系统可能在等待选择或阻止了切换。")
    }
    private suspend fun torch(on: Boolean): ActionResult {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return failed("请回到首页重新开启语音，并允许相机权限，才能控制手电筒。")
        val manager=context.getSystemService(CameraManager::class.java)
        return try {
            VoiceCamera.activity?.let { it.torch(on);return done(if(on)"手电筒已打开。" else "手电筒已关闭。") }
            if(torchSwitch==null) {
                val id=manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).let { c -> c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)==true && c.get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK } } ?: return failed("这台设备没有可用的手电筒。")
                val control=AcknowledgedSwitch { enabled -> manager.setTorchMode(id,enabled) }
                val callback=object:CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId:String, enabled:Boolean) { if(cameraId==id)control.observed(enabled) }
                    override fun onTorchModeUnavailable(cameraId:String) { if(cameraId==id)control.unavailable() }
                }
                manager.registerTorchCallback(callback,Handler(Looper.getMainLooper()))
                torchManager=manager;torchCallback=callback;torchSwitch=control
            }
            torchSwitch!!.set(on)
            done(if(on)"手电筒已打开。" else "手电筒已关闭。")
        } catch(e:CancellationException) { if(e is TimeoutCancellationException)failed("系统还没有确认手电筒状态，请检查手电筒。") else  throw e }
        catch(e:Exception) { failed("手电筒操作失败：${e.message}。") }
    }
    private suspend fun camera(action:String):ActionResult = try {
        when(action) {
            "open" -> {VoiceCamera.open(context);done("已打开 OpenNomi 语音相机，可以直接说拍照、自拍或退出相机。")}
            "capture", "selfie" -> {
                val camera=VoiceCamera.open(context)
                if(action=="selfie") {camera.switchCamera(true);delay(700)}
                done(camera.takePhoto())
            }
            "switch" -> {VoiceCamera.open(context).switchCamera();done("已切换摄像头。")}
            "close" -> {
                if(VoiceCamera.close())done("已退出语音相机。")
                else {
                    val service=ScreenAccessService.instance
                    if(service!=null && service.currentPackage().contains("camera",true) && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))done("已退出相机，回到桌面。")
                    else failed("当前没有打开语音相机；如需退出其他应用，可以说退出应用。")
                }
            }
            else -> failed("相机指令不支持。")
        }
    } catch(e:CancellationException) { if(e is TimeoutCancellationException)failed("相机操作超时，请检查相机权限或是否被其他应用占用。") else throw e }
    catch(e:Exception) {failed("相机操作未完成：${e.message}。")}
}
