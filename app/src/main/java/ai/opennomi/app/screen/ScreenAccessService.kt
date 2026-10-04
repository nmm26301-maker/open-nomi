package ai.opennomi.app.screen

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.graphics.Rect
import android.os.Handler
import android.os.Looper

class ScreenAccessService: AccessibilityService() {
    companion object { @Volatile var instance: ScreenAccessService? = null }
    private val main=Handler(Looper.getMainLooper())
    private val refresh=Runnable { readPage() }
    override fun onServiceConnected() { instance=this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(!ScreenState.state.value.active) return
        main.removeCallbacks(refresh); main.postDelayed(refresh,300)
    }
    override fun onInterrupt() { ScreenState.event("无障碍读取已中断") }
    override fun onDestroy() { if(instance===this)instance=null;main.removeCallbacksAndMessages(null);super.onDestroy() }
    @Suppress("DEPRECATION")
    fun readPage(): Page {
        if(!ScreenState.state.value.active) return Page()
        val root=rootInActiveWindow ?: run {
            if(!ScreenState.ownForeground)ScreenState.update { it.copy(page=Page(version=it.page.version+1)) }
            return Page()
        }
        try {
            val app=root.packageName?.toString().orEmpty()
            // Keep the observed external page while OpenNomi dialogs are in front.
            if(app == packageName) return ScreenState.state.value.page
            val lines=mutableListOf<String>(); val nodes=mutableListOf<ScreenNode>(); var sensitive=false; var count=0
            fun visit(n: AccessibilityNodeInfo) {
                if(count++ >= 300) return
                if(n.isPassword) { sensitive=true; return }
                if(n.isVisibleToUser) {
                    val text=(n.text ?: n.contentDescription)?.toString()?.take(500).orEmpty()
                    if(text.isNotBlank())lines+=text
                    if(n.isEditable || n.isClickable) { val rect=Rect();n.getBoundsInScreen(rect);nodes+=ScreenNode(nodes.size,text,n.isEditable,n.isClickable,rect.flattenToString(),n.isSelected || n.isChecked) }
                }
                for(i in 0 until n.childCount) n.getChild(i)?.let { child -> try{visit(child)}finally{child.recycle()} }
            }
            visit(root)
            val old=ScreenState.state.value.page; val text=lines.distinct().joinToString("\n").take(16000)
            val p=Page(app,text,nodes,sensitive,if(old.app==app && old.text==text && old.nodes==nodes && old.sensitive==sensitive)old.version else old.version+1)
            ScreenState.update { it.copy(page=p) }; if(!sensitive && ScreenState.state.value.translation && !ScreenState.state.value.audio)ScreenShareService.instance?.translateLine(TranslationText.foreignLines(text)); return p
        } finally { root.recycle() }
    }
    @Suppress("DEPRECATION")
    fun execute(step: Step, expected: Page): Boolean {
        check(ScreenState.state.value.active) { "屏幕共享已停止" }
        val now=readPage(); check(now.app==expected.app && now.version==expected.version && !now.sensitive) { "页面已变化，请重新规划" }
        if(step.kind=="back")return performGlobalAction(GLOBAL_ACTION_BACK)
        val root=rootInActiveWindow ?: return false
        try {
            check(root.packageName?.toString()==expected.app) { "请回到目标应用再执行" }
            val actionable=mutableListOf<AccessibilityNodeInfo>();var visited=0
            fun visit(n: AccessibilityNodeInfo) { if(visited++>=300)return;if(n.isPassword)return;if(n.isVisibleToUser&&(n.isEditable||n.isClickable))actionable+=AccessibilityNodeInfo.obtain(n); for(i in 0 until n.childCount)n.getChild(i)?.let { c -> try{visit(c)}finally{c.recycle()} } }
            visit(root)
            try {
                if(step.kind=="scroll") {
                    val direction=if(step.text=="up")AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    fun scroll(n: AccessibilityNodeInfo): Boolean { if(n.isScrollable && n.performAction(direction))return true; for(i in 0 until n.childCount)n.getChild(i)?.let{ c -> try{if(scroll(c))return true}finally{c.recycle()} };return false };return scroll(root)
                }
                val node=actionable.getOrNull(step.node) ?: return false
                return when(step.kind){ "click" -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK);"type" -> node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,step.text.take(2000)) });else->false }
            }finally{actionable.forEach{it.recycle()}}
        }finally{root.recycle()}
    }
}
