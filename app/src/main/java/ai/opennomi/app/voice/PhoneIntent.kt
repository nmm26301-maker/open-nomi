package ai.opennomi.app.voice

/** Only actual user speech can start a phone task; questions and negations are not tasks. */
object PhoneIntent {
    /** Explicit user goal only: launch first so the planner can see the target app. */
    fun initialApp(raw: String): String? {
        val goal = goal(raw) ?: return null
        return Regex("^(?:打开|启动)(.+?)(?=搜索|查找|输入|打字|点击|然后|，|,)").find(goal)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it.length <= 30 }
    }
    fun goal(raw: String): String? {
        val text=raw.trim().replace(Regex("^(小智[，, ]*|请|帮我|给我|麻烦你)+"), "").trim()
        if(Regex("^(不要|别|禁止)|怎么|如何|为什么|能不能|可以吗|吗[？?]?$|是什么").containsMatchIn(text))return null
        return text.takeIf { Regex("^(打开|启动|关闭|关掉|退出|点击|点开|点个|输入|打字|在.+(?:搜索|输入|点击)|搜索|拍|自拍|切换|返回|回到|向[上下]|往[上下])").containsMatchIn(it) }
    }
}
