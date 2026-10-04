package ai.opennomi.app.voice

/** Only the user's recognized utterance is parsed. Screen/model text is never an authority. */
data class VoiceCommand(val action: String, val target: String = "")
object VoiceCommands {
    fun parse(raw: String): VoiceCommand? {
        val s = raw.trim().trim('。','！','!','？','?').replace(Regex("^(小智[，, ]*|请|帮我|给我|麻烦你)+"), "").trim()
        return when {
            s in setOf("暂停语音", "停止聊天", "暂停聊天", "停止聆听", "关闭麦克风") -> VoiceCommand("stop")
            s in setOf("确认执行", "确认", "执行确认") -> VoiceCommand("confirm")
            s in setOf("取消", "取消执行", "不要执行") -> VoiceCommand("cancel")
            s in setOf("打开手电筒", "开启手电筒", "打开闪光灯") -> VoiceCommand("torch", "on")
            s in setOf("关闭手电筒", "关掉手电筒", "关闭闪光灯") -> VoiceCommand("torch", "off")
            s in setOf("返回", "返回上一页", "退回上一页") -> VoiceCommand("back")
            s in setOf("回到桌面", "返回桌面", "回到主页") -> VoiceCommand("home")
            s in setOf("打开通知栏", "展开通知栏") -> VoiceCommand("notifications")
            s in setOf("向下滚动", "往下翻", "下一页", "下滑") -> VoiceCommand("scroll", "down")
            s in setOf("向上滚动", "往上翻", "上一页", "上滑") -> VoiceCommand("scroll", "up")
            s in setOf("点赞", "点个赞", "点一个赞", "给它点赞", "给这个点个赞") -> VoiceCommand("like")
            Regex("(?:点击|点)第([一二三四五六七八九]|[1-9])个?点赞(?:按钮)?").matches(s) -> {
                val value=Regex("第(.)").find(s)!!.groupValues[1]
                VoiceCommand("like",if(value[0].isDigit())value else ("一二三四五六七八九".indexOf(value)+1).toString())
            }
            s.startsWith("点击") && s.length > 2 -> VoiceCommand("click", s.removePrefix("点击").trim().removeSuffix("按钮"))
            s.startsWith("输入") && s.length > 2 -> VoiceCommand("type", s.removePrefix("输入").trim())
            else -> null
        }
    }
    fun sensitive(label: String): Boolean = Regex("支付|付款|转账|购买|下单|发送|发布|删除|清空|卸载|授权|允许|确认订单|提交|send|publish|pay|delete|buy|allow", RegexOption.IGNORE_CASE).containsMatchIn(label)
    fun likeLabel(label: String): Boolean = !Regex("已点赞|取消赞|取消点赞|unlike|liked", RegexOption.IGNORE_CASE).containsMatchIn(label) &&
        (label.trim() in setOf("赞", "点赞", "喜欢") || Regex("未点赞|点赞|(?<![A-Za-z])like(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(label))
}
