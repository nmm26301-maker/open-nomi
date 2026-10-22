package ai.opennomi.app.screen

/** Official Hy-MT2 user-only translation instruction; context is explicitly background. */
object HyMtPrompt {
    fun create(text:String,previous:String=""):String {
        val input=text.trim()
        require(input.isNotEmpty()){"请输入原文"}
        require(input.length<=1000){"Hy-MT2 单次原文最多 1000 字，请分段翻译"}
        val context=previous.trim().takeLast(160)
        return if(context.isEmpty())"将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n$input"
        else "【背景信息】\n$context\n\n请结合背景信息将以下文本翻译为中文，注意只输出待翻译文本的译文，不要重复背景或额外解释：\n\n【待翻译文本】\n$input"
    }
}
