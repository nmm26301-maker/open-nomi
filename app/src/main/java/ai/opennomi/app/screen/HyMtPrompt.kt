package ai.opennomi.app.screen

/** Official current-text-only instruction: prior speech must not become text to translate. */
object HyMtPrompt {
    @Suppress("UNUSED_PARAMETER")
    fun create(text:String,previous:String=""):String {
        val input=text.trim()
        require(input.isNotEmpty()){"请输入原文"}
        require(input.length<=1000){"Hy-MT2 单次原文最多 1000 字，请分段翻译"}
        return "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n$input"
    }
}
