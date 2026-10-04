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
    private data class Pending(val step: Step, val page: Page, val label: String, val expires: Long)
    private var pending: Pending? = null
    fun clear() { pending = null }
    suspend fun execute(command: VoiceCommand): String {
        if(command.action == "cancel") { pending=null; return "已取消，我继续听你说。" }
        if(command.action == "torch") { pending=null; return torch(command.target == "on") }
        val service = ScreenAccessService.instance ?: return "请先在系统设置中开启 OpenNomi 无障碍服务，我才能替你操作。"
        if(command.action == "open_app") { pending=null;return openApp(service,command) }
        if(command.action in setOf("home", "back", "notifications")) {
            pending=null
            val action=when(command.action) { "home" -> AccessibilityService.GLOBAL_ACTION_HOME; "back" -> AccessibilityService.GLOBAL_ACTION_BACK; else -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS }
            return if(service.performGlobalAction(action)) "已${when(command.action){"home"->"回到桌面";"back"->"返回";else->"展开通知栏"}}。" else "系统没有接受这次操作，请稍后再说一次。"
        }
        if(!ScreenState.state.value.active)return "请先开启屏幕共享，我才能定位页面按钮。"
        val page=service.readPage()
        if(page.sensitive)return "当前页面包含密码框，请先切换页面。"
        if(command.action == "confirm") {
            val p=pending ?: return "目前没有等待确认的操作。"
            pending=null
            if(SystemClock.elapsedRealtime()>p.expires)return "确认已过期，请重新说要执行的操作。"
            return apply(service,p.step,p.page,p.label)
        }
        pending=null
        val step: Step; val label: String
        when(command.action) {
            "scroll" -> { step=Step("scroll",text=command.target);label=if(command.target=="up")"向上滚动" else "向下滚动" }
            "like", "click" -> {
                val nodes=page.nodes.filter { it.clickable && if(command.action=="like")!it.selected && VoiceCommands.likeLabel(it.text) else it.text.trim()==command.target }
                if(nodes.isEmpty())return if(command.action=="like")"没有找到未点赞的按钮，我没有点击。" else "没有找到${command.target}按钮，我没有点击。"
                val ordinal=(command.ordinal ?: if(command.action=="like")command.target.toIntOrNull() else null)?.minus(1)
                if(nodes.size>1 && ordinal==null)return if(command.action=="like")"看到了${nodes.size}个点赞按钮，请说点击第一个点赞按钮，或指定第几个。" else "看到了${nodes.size}个${command.target}按钮，请说点击第一个${command.target}按钮，或指定第几个。"
                val node=if(ordinal!=null)nodes.getOrNull(ordinal) ?: return "没有找到你指定的第${ordinal+1}个按钮。" else nodes.single()
                step=Step("click",node.id);label=if(command.action=="like")"点击点赞按钮" else "点击${node.text}"
                if(VoiceCommands.sensitive(node.text)) { pending=Pending(step,page,label,SystemClock.elapsedRealtime()+90000); return "准备${label}。请在九十秒内说确认执行，或说取消。" }
            }
            "type" -> {
                val nodes=page.nodes.filter{it.editable}
                if(nodes.size!=1)return "请切到只有一个输入框的页面，我才能确定输入位置。"
                step=Step("type",nodes.single().id,command.target);label="输入文字"
            }
            else -> return "这条操作暂时不支持。"
        }
        return apply(service,step,page,label)
    }
    private fun apply(service: ScreenAccessService, step: Step, page: Page, label: String): String = try {
        if(service.execute(step,page)) "已${label}。" else "${label}未成功，当前控件没有响应。"
    } catch(e:Exception) { "没有执行：${e.message}。请重新说一次。" }
    @Suppress("DEPRECATION")
    private suspend fun openApp(service:ScreenAccessService, command:VoiceCommand):String {
        val pm=context.packageManager
        val entries=pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            .filter { it.activityInfo.exported && it.activityInfo.enabled }
            .map { LauncherTarget(it.loadLabel(pm).toString(),it.activityInfo.packageName) }
        val matches=LauncherTargets.matching(command.target,entries)
        if(matches.isEmpty())return "没有找到${command.target}，请说手机上显示的完整应用名称。"
        if(matches.size>1 && command.ordinal==null)return "找到了${matches.size}个${command.target}，请说打开第一个${command.target}，或指定第几个。"
        val target=matches.getOrNull((command.ordinal ?: 1)-1) ?: return "没有找到你指定的应用。"
        val launch=pm.getLaunchIntentForPackage(target.packageName) ?: return "这个应用没有可打开的首页。"
        service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val opened=withTimeoutOrNull(4000) {
            while(isActive) { if(service.currentPackage()==target.packageName)return@withTimeoutOrNull true;delay(100) }
            false
        }==true
        return if(opened)"已打开${target.label}。" else "还没有确认打开${target.label}，系统可能在等待选择或阻止了切换。"
    }
    private suspend fun torch(on: Boolean): String {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return "请回到首页重新开启语音，并允许相机权限，才能控制手电筒。"
        val manager=context.getSystemService(CameraManager::class.java)
        return try {
            val id=manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).let { c -> c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)==true && c.get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK } } ?: return "这台设备没有可用的手电筒。"
            val done=CompletableDeferred<Unit>()
            var requested=false
            val callback=object:CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId:String, enabled:Boolean) { if(requested && cameraId==id && enabled==on)done.complete(Unit) }
            }
            manager.registerTorchCallback(callback,Handler(Looper.getMainLooper()))
            try { requested=true;manager.setTorchMode(id,on);withTimeout(3000){done.await()};if(on)"手电筒已打开。" else "手电筒已关闭。" }
            finally { manager.unregisterTorchCallback(callback) }
        } catch(e:CancellationException) { if(e is TimeoutCancellationException)"系统还没有确认手电筒状态，请检查手电筒。" else  throw e }
        catch(e:Exception) { "手电筒操作失败：${e.message}。" }
    }
}
