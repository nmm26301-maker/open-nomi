package ai.opennomi.app.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

data class ScreenFrame(val jpeg: ByteArray, val width: Int, val height: Int, val time: Long, val session: Long, val pageApp: String = "", val pageVersion: Long = 0)
data class ScreenNode(val id: Int, val text: String, val editable: Boolean, val clickable: Boolean, val bounds: String)
data class Page(val app: String = "", val text: String = "", val nodes: List<ScreenNode> = emptyList(), val sensitive: Boolean = false, val version: Long = 0)
data class Step(val kind: String, val node: Int = -1, val text: String = "", val x: Int = -1, val y: Int = -1) {
    fun describe() = when(kind) { "click" -> "点击控件 #$node"; "type" -> "在 #$node 填写：$text"; "back" -> "返回上一页"; "scroll" -> "向下滚动"; "tap" -> "点击位置 ($x, $y)"; "finish" -> text; else -> "不支持的操作" }
}
data class WorkspaceState(val active: Boolean = false, val session: Long = 0, val status: String = "共享尚未开启", val page: Page = Page(), val reply: String = "", val busy: Boolean = false, val translation: Boolean = false, val audio: Boolean = false, val caption: String = "", val proposed: Step? = null, val proposedPage: Page? = null, val task: String = "", val agentRunning: Boolean = false, val history: List<String> = emptyList(), val notice: String = "", val voiceStatus: String = "", val voiceOn: Boolean = false)
object ScreenState {
    private val generation = AtomicLong(0)
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    @Volatile var ownForeground = false
    @Volatile var frame: ScreenFrame? = null
    @Synchronized fun update(block: (WorkspaceState) -> WorkspaceState) { mutable.value = block(mutable.value) }
    fun begin(): Long { val id = generation.incrementAndGet(); frame = null; mutable.value = WorkspaceState(active=true, session=id, status="屏幕共享中 · 内容保留在本机"); return id }
    fun end() { generation.incrementAndGet(); frame = null; update { it.copy(active=false, session=generation.get(), busy=false, translation=false, audio=false, page=Page(), proposed=null, proposedPage=null, agentRunning=false, status="共享已停止") } }
    fun valid(id: Long) = state.value.active && state.value.session == id
    fun event(message: String) = update { it.copy(status=message, history=(it.history + message).takeLast(60)) }
}
/** Latest subtitle waits until the previous one has had enough time to be read. */
class CaptionGate {
    private var current = ""; private var until = 0L; private var pending = ""
    fun offer(text: String, now: Long): String? {
        if(text.isBlank() || text == current || text == pending) return null
        pending=text
        return tick(now)
    }
    fun tick(now: Long): String? {
        if(now < until || pending.isBlank()) return null
        current=pending; pending=""
        until=now + (current.length * 80L).coerceIn(2500L, 10000L)
        return current
    }
}
object StepParser {
    fun parse(raw: String): Step? {
        val json = raw.substringAfter("```json", raw).substringBefore("```").trim()
        runCatching { val j=JSONObject(json.substring(json.indexOf('{'),json.lastIndexOf('}')+1)); return Step(j.getString("action"),j.optInt("node",-1),j.optString("text"),j.optInt("x",-1),j.optInt("y",-1)).takeIf { it.kind in setOf("click","type","back","scroll","tap","finish") } }
        // AutoGLM action protocol, parsed as data; never evaluate generated code.
        fun value(name:String)=Regex("$name\\s*=\\s*[\"']([^\"']*)[\"']").find(raw)?.groupValues?.get(1).orEmpty()
        if(raw.contains("finish(message=")) return Step("finish",text=value("message"))
        return when(value("action")) {
            "Back" -> Step("back")
            "Tap" -> Regex("element\\s*=\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]").find(raw)?.let { Step("tap", x=it.groupValues[1].toInt(), y=it.groupValues[2].toInt()) }?.takeIf { it.x in 0..999 && it.y in 0..999 }
            "Swipe" -> if(value("direction")=="down") Step("scroll") else null
            else -> null
        }
    }
}
