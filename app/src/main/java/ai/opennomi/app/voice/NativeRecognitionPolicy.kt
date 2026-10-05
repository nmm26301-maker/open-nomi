package ai.opennomi.app.voice

/** Android recognition error numbers; kept pure for restart/backoff regressions. */
object NativeRecognitionPolicy {
    fun delay(error: Int, failures: Int): Long? = when (error) {
        6, 7 -> 700L // speech timeout / no match: normal silence
        1, 2, 3, 4, 5, 8, 11 -> if (failures <= 5) (700L * failures.coerceAtLeast(1)).coerceAtMost(5000) else null
        else -> null // permission / quota / language: show the reason, stop retrying
    }
    fun message(error: Int): String = when(error) {
        1, 2 -> "系统语音识别网络不可用，请检查网络后重试"
        3 -> "麦克风暂时不可用，请关闭其他录音应用后重试"
        8 -> "系统语音识别正忙"
        9 -> "麦克风权限未允许"
        10 -> "系统语音识别请求过于频繁，请稍后重试"
        12, 13 -> "系统尚未提供中文识别，请在手机语音输入设置中启用中文"
        else -> "系统语音识别暂时不可用（$error），请检查手机语音输入服务"
    }
}

object NativeCommandRouter {
    fun route(text: String): ControlRequest? {
        VoiceTasks.agentGoal(text)?.let { return ControlRequest(goal = it) }
        val sequence = VoiceTasks.parse(text)
        if (sequence != null) return if (sequence.error.isBlank()) ControlRequest(sequence.commands) else null
        VoiceCommands.parse(text)?.let { return ControlRequest(listOf(it)) }
        return PhoneIntent.goal(text)?.let { ControlRequest(goal = it) }
    }
}
