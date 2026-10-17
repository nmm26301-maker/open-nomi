package ai.opennomi.app.screen

enum class SubtitlePosition(val label:String) {
    TOP("顶部"), MIDDLE("中部"), BOTTOM("底部");
    companion object { fun selected(value:String)=entries.firstOrNull{it.name==value} ?: BOTTOM }
}

data class SubtitleSection(val text:String,val translated:Boolean=false)

/** Compose from the foreground service state, independently of the Activity lifecycle. */
object FloatingSubtitle {
    const val WINDOW_ALPHA=.78f
    fun sections(state:WorkspaceState,visible:Boolean,original:Boolean,phone:Boolean):List<SubtitleSection> {
        if(!state.active || !visible)return emptyList()
        if(!state.translation) {
            if(state.captionHistory.isNotEmpty())return listOf(
                SubtitleSection("视频字幕已暂停 · 点悬浮球继续"),
                SubtitleSection("上一句：${state.captionHistory.last().translated}",true)
            )+listOfNotNull(state.notice.takeIf{it.isNotBlank()}?.let{SubtitleSection(it.take(240))})
            return listOfNotNull(state.notice.takeIf{it.isNotBlank()}?.let{SubtitleSection(it.take(240))})
        }
        val rows=mutableListOf(SubtitleSection(if(state.audio)"OpenNomi · 视频实时字幕" else "OpenNomi · 画面文字翻译"))
        if(state.caption.isNotBlank()) {
            rows+=SubtitleSection(state.caption,true)
            if(state.audio && original && state.captionOriginal.isNotBlank())rows+=SubtitleSection("原文：${state.captionOriginal.take(300)}")
        } else {
            rows+=SubtitleSection(if(state.audioPreview.isNotBlank())"识别中：${state.audioPreview.take(180)}" else if(state.audio)"正在等待视频原声…" else "正在等待画面外语文字…")
        }
        if(state.notice.isNotBlank())rows+=SubtitleSection(state.notice.take(240))
        if(phone && state.caption.isNotBlank())rows+=SubtitleSection("由 Google 翻译")
        return rows
    }
}
