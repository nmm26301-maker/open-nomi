package ai.opennomi.app.voice

data class LauncherTarget(val label: String, val packageName: String)
object LauncherTargets {
    private fun key(value: String): String {
        val name=value.lowercase().replace(Regex("\\s+"),"").removeSuffix("app")
        return when(name) { "wechat", "微信" -> "微信"; "b站", "bilibili", "哔哩哔哩" -> "哔哩哔哩"; else -> name }
    }
    fun matching(name: String, installed: List<LauncherTarget>): List<LauncherTarget> {
        if(name.isBlank())return emptyList()
        return installed.filter { key(it.label)==key(name) }.distinctBy { it.packageName }.sortedBy { it.packageName }
    }
}
