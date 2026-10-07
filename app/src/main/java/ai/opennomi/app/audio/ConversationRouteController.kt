package ai.opennomi.app.audio

enum class RouteKind { BLUETOOTH, WIRED, SPEAKER, EARPIECE }
data class ConversationDevice(val id: Int, val kind: RouteKind, val name: String)
data class ConversationRoute(val active: Boolean = false, val pending: Boolean = false,
    val device: ConversationDevice? = null, val message: String = "耳机自动切换 · 对话未开启", val request: Long = 0)

/** Selection is a request; only platform confirmation makes a route ready. Main-thread confined. */
interface ConversationRoutePlatform {
    fun devices(): List<ConversationDevice>
    fun current(): ConversationDevice?
    fun enter()
    fun select(device: ConversationDevice): Boolean
    fun exit()
}

class ConversationRouteController(private val platform: ConversationRoutePlatform,
    private val publish: (ConversationRoute) -> Unit,
    private val timeout: (Long) -> Unit) {
    private val owners = mutableSetOf<Any>()
    private var failed = mutableSetOf<Int>()
    private var inventory = emptySet<Int>()
    private var requested: ConversationDevice? = null
    var state = ConversationRoute(); private set
    private var serial = 0L
    val active get() = owners.isNotEmpty()
    fun acquire(owner: Any) {
        if (!owners.add(owner)) return
        if (owners.size == 1) {
            try { platform.enter(); refresh() }
            catch (e: Exception) { owners.remove(owner); runCatching { platform.exit() }; throw e }
        }
    }
    fun release(owner: Any) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        serial++; requested = null; failed.clear(); inventory = emptySet()
        platform.exit(); update(ConversationRoute(request = serial))
    }
    fun devicesChanged() {
        if (!active) return
        val next = platform.devices().map { it.id }.toSet()
        if (next != inventory) failed.clear()
        refresh()
    }
    private fun refresh() {
        if (!active) return
        val devices = platform.devices(); inventory = devices.map { it.id }.toSet()
        val next = devices.filter { it.id !in failed }.minByOrNull { it.kind.ordinal }
        if (next == null) {
            requested = null; update(ConversationRoute(true, false, message = "音频通道不可用 · 请结束其他通话后重试", request = ++serial)); return
        }
        if (requested?.id == next.id && (state.pending || state.device?.id == next.id)) return
        requested = next
        val request = ++serial
        update(ConversationRoute(true, true, message = if (next.kind == RouteKind.BLUETOOTH) "正在连接蓝牙耳机…" else "正在切换声音…", request = request))
        if (!platform.select(next)) { expired(request); return }
        confirmed(platform.current())
        if (state.pending && state.request == request) timeout(request)
    }
    fun confirmed(device: ConversationDevice?) {
        if (!active || device == null || device.id != requested?.id) return
        val hint = if (failed.isNotEmpty()) " · 耳机通道未就绪，已回退" else ""
        val label = when (device.kind) {
            RouteKind.BLUETOOTH -> "蓝牙耳机 · 播放与耳机麦克风"
            RouteKind.WIRED -> "有线耳机 · 播放与耳机麦克风"
            RouteKind.SPEAKER -> "手机扬声器 · 手机麦克风"
            RouteKind.EARPIECE -> "手机听筒 · 手机麦克风"
        }
        update(ConversationRoute(true, false, device, label + hint, state.request))
    }
    fun expired(request: Long) {
        if (!active || !state.pending || state.request != request) return
        requested?.let { failed.add(it.id) }; requested = null; refresh()
    }
    private fun update(next: ConversationRoute) { state = next; publish(next) }
}
