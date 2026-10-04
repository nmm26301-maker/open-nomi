package ai.opennomi.app.voice

/** Only the user's recognized utterance is parsed. Screen/model text is never an authority. */
data class VoiceCommand(val action: String, val target: String = "", val ordinal: Int? = null)
object VoiceCommands {
    fun parse(raw: String): VoiceCommand? {
        val s = raw.trim().trim('。','！','!','？','?').replace(Regex("^(小智[，, ]*|请|帮我|给我|麻烦你)+"), "").trim()
        return when {
            s in setOf("暂停语音", "停止聊天", "暂停聊天", "停止聆听", "关闭麦克风") -> VoiceCommand("stop")
            s in setOf("确认执行", "确认", "执行确认") -> VoiceCommand("confirm")
            s in setOf("取消", "取消执行", "不要执行", "取消任务", "停止任务", "停止操作") -> VoiceCommand("cancel")
            s in setOf("暂停任务", "暂停操作") -> VoiceCommand("pause_task")
            s in setOf("继续任务", "继续操作") -> VoiceCommand("resume_task")
            s in setOf("打开手电筒", "开启手电筒", "打开闪光灯", "把手电筒打开", "把手电筒开启") -> VoiceCommand("torch", "on")
            s in setOf("关闭手电筒", "关掉手电筒", "关闭闪光灯", "关掉闪光灯", "把手电筒关掉", "把手电筒关闭", "把手电筒关了") -> VoiceCommand("torch", "off")
            s in setOf("打开相机", "开启相机", "打开照相机", "打开摄像头") -> VoiceCommand("camera", "open")
            s in setOf("拍照", "拍张照", "拍一张照片", "拍一张", "照相", "拍个照", "拍张照片") -> VoiceCommand("camera", "capture")
            s in setOf("自拍", "拍张自拍", "拍一张自拍") -> VoiceCommand("camera", "selfie")
            s in setOf("切换摄像头", "切换镜头", "切换相机") -> VoiceCommand("camera", "switch")
            s in setOf("退出相机", "关闭相机", "关掉相机", "退出照相机") -> VoiceCommand("camera", "close")
            s in setOf("语音诊断", "报告当前状态", "检查语音", "检查权限") -> VoiceCommand("diagnostics")
            s in setOf("返回", "返回上一页", "退回上一页") -> VoiceCommand("back")
            s in setOf("回到桌面", "返回桌面", "回到主页") -> VoiceCommand("home")
            s in setOf("退出应用", "退出这个应用", "退出当前应用", "关闭应用", "关闭这个应用", "关闭当前应用", "退出软件") -> VoiceCommand("exit_app")
            s in setOf("打开通知栏", "展开通知栏") -> VoiceCommand("notifications")
            s in setOf("向下滚动", "往下翻", "下一页", "下滑") -> VoiceCommand("scroll", "down")
            s in setOf("向上滚动", "往上翻", "上一页", "上滑") -> VoiceCommand("scroll", "up")
            s in setOf("保存这页", "保存页面文字", "保存屏幕文字", "保存碎片") -> VoiceCommand("save_page")
            s.startsWith("记住") && s.length>2 -> VoiceCommand("remember",s.removePrefix("记住").trim())
            s in setOf("点赞", "点个赞", "点一个赞", "给它点赞", "给这个点个赞") -> VoiceCommand("like")
            Regex("(?:点击|点)第([一二三四五六七八九]|[1-9])个?点赞(?:按钮)?").matches(s) -> {
                val value=Regex("第(.)").find(s)!!.groupValues[1]
                VoiceCommand("like",if(value[0].isDigit())value else ("一二三四五六七八九".indexOf(value)+1).toString())
            }
            Regex("(?:打开|启动)第([一二三四五六七八九]|[1-9])个(.+)").matches(s) -> {
                val m=Regex("(?:打开|启动)第(.)个(.+)").find(s)!!
                VoiceCommand("open_app",m.groupValues[2].trim(),ordinal(m.groupValues[1]))
            }
            Regex("点击第([一二三四五六七八九]|[1-9])个(.+)").matches(s) -> {
                val m=Regex("点击第(.)个(.+)").find(s)!!
                VoiceCommand("click",m.groupValues[2].trim().removeSuffix("按钮"),ordinal(m.groupValues[1]))
            }
            Regex("(?:打开|启动)(.+)").matches(s) && !Regex("怎么|如何|为什么|吗|然后|之后|搜索|输入|打字|拍照|，|,").containsMatchIn(s) -> VoiceCommand("open_app",s.removePrefix("打开").removePrefix("启动").trim())
            s.startsWith("点击") && s.length > 2 -> VoiceCommand("click", s.removePrefix("点击").trim().removeSuffix("按钮"))
            s.startsWith("输入") && s.length > 2 -> VoiceCommand("type", s.removePrefix("输入").trim().let { if((it.startsWith("“") && it.endsWith("”")) || (it.startsWith("\"") && it.endsWith("\"")))it.substring(1,it.length-1) else it })
            s.startsWith("打字") && s.length>2 -> VoiceCommand("type",s.removePrefix("打字").trim().trim('“','”','"'))
            else -> null
        }
    }
    private fun ordinal(value:String) = value.toIntOrNull() ?: ("一二三四五六七八九".indexOf(value)+1)
    fun sensitive(label: String): Boolean = Regex("支付|付款|转账|购买|下单|发送|发布|删除|清空|卸载|授权|允许|确认订单|提交|send|publish|pay|delete|buy|allow", RegexOption.IGNORE_CASE).containsMatchIn(label)
    fun likeLabel(label: String): Boolean = !Regex("已赞|已点赞|已喜欢|取消喜欢|取消赞|取消点赞|unlike|liked", RegexOption.IGNORE_CASE).containsMatchIn(label) &&
        (label.trim() in setOf("赞", "点赞", "喜欢") || Regex("未点赞|点赞|(?<![A-Za-z])like(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(label))
}
