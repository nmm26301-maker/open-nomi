package ai.opennomi.app.screen

data class SetupLink(val label: String, val url: String)
data class SetupTopic(val title: String, val steps: List<String>, val links: List<SetupLink> = emptyList())

/** Phone-oriented help. Kept local so setup remains readable before any account works. */
object SetupGuide {
    val topics = listOf(
        SetupTopic("先准备哪些东西", listOf(
            "视觉问答、Agent 和模型文字翻译共用一个视觉模型连接。先申请智谱 API Key 即可，无需为这三项分别开户。",
            "原声 NOMI 使用小智设备绑定。AIRI 的服务设置独立保存，需要在 AIRI 页面单独配置。",
            "中英文 OCR、碎片本、记忆和英语视频识别数据已内置；它们不需要注册账号。翻译成中文仍需语言模型、NOMI 或翻译服务。"
        )),
        SetupTopic("智谱视觉模型 · 手机申请", listOf(
            "打开智谱官方平台，按页面提示注册或登录。如果平台要求账号验证，按它当前显示的要求完成。",
            "进入 API Keys 管理页，创建一个应用用的密钥，例如命名 OpenNomi，然后复制完整密钥。",
            "回到连接页，选择 GLM 免费视觉模型，将密钥粘贴到 API Key 栏。",
            "点保存并测试看图。识别测试图片成功后，回到看屏幕开启共享，或在碎片本添加图片提问。",
            "地址：https://open.bigmodel.cn/api/paas/v4；模型：glm-4.6v-flash。官方目前列为免费模型，调用仍受账号权限、额度和限流规则约束。密钥只填到手机里。"
        ), listOf(SetupLink("创建智谱 API Key", VisionApi.KEY_PAGE), SetupLink("智谱官方申请说明", "https://docs.bigmodel.cn/cn/guide/develop/openai/introduction"))),
        SetupTopic("原声 NOMI · 绑定设备", listOf(
            "回到 OpenNomi 首页连接 NOMI。如果显示绑定验证码，先记下本次验证码。",
            "打开小智官方后台并登录，在设备管理或智能体的添加设备入口输入当前验证码。后台页面名称可能随版本变化。",
            "返回 OpenNomi，重新连接，再点开始说话。如果验证码过期，刷新连接获取新的验证码；已经绑定的设备通常无需重新登记。",
            "设备绑定与智谱 API Key 是两套连接，填写视觉密钥不会自动完成原声 NOMI 绑定。"
        ), listOf(SetupLink("小智设备管理", "https://xiaozhi.me/console/"))),
        SetupTopic("AIRI · 先让文字对话成功", listOf(
            "在 AIRI 页面进入设置 → 服务商 → 聊天。使用自己的兼容服务时选择 OpenAI 兼容 API，填写该服务的密钥和官方 API 根地址。",
            "执行页面提供的 Ping API 或服务验证，然后在设置 → 模块 → 意识/思维选择这个服务和实际可用的模型。先发送一条文字消息检查回复。",
            "如果复用智谱账号，需要在 AIRI 里再次填写密钥与根地址 https://open.bigmodel.cn/api/paas/v4；选择平台当前允许调用的聊天模型。服务商列表能否加载、所用模型是否适配，需要以 AIRI 实测结果为准。",
            "手机听觉使用 Android 系统识别，但 AIRI 仍需聊天服务。若手机没有识别服务，先用文字输入。",
            "角色说话还需要设置言语/语音合成服务和声音；GLM 视觉密钥不会自动提供合成声音。也可以用 AIRI 引导里的官方账号登录选项，权限和费用以该页面为准。"
        ), listOf(SetupLink("AIRI 官方配置说明", "https://airi.moeru.ai/docs/zh-Hans/docs/manual/config/providers/consciousness/openai"), SetupLink("AIRI 网页使用说明", "https://airi.moeru.ai/docs/en/docs/manual/web/"))),
        SetupTopic("屏幕共享和悬浮球 · 系统权限", listOf(
            "看屏幕 → 允许悬浮窗，开启 OpenNomi 的在其他应用上层显示权限。",
            "Agent 还需要开启 OpenNomi 无障碍服务；只问图片无需无障碍。无障碍可以读取页面控件并执行你确认的操作。",
            "Android 13 若提示受限设置：系统设置 → 应用 → OpenNomi → 右上角更多 → 允许受限制的设置，然后再进入无障碍设置开启服务。",
            "点开启屏幕共享，在系统弹窗同意，然后切到目标 App。通知权限用于更容易找到正在共享的通知和停止入口。每次新共享仍要系统授权。",
            "若小米系统阻止打开外部 App 时显示球球，检查 OpenNomi 的悬浮窗/后台弹出界面权限；具体名称随系统版本不同。锁屏、受保护播放画面和密码输入页可能无法读取。"
        ), listOf(SetupLink("Android 受限设置说明", "https://support.google.com/android/answer/12623953?hl=zh-Hans"))),
        SetupTopic("翻译 · 先测一句，再开浮条", listOf(
            "先在连接页测试已保存配置的文字翻译。成功后开启屏幕共享，切到英语或其他非中文页面，再开启实时翻译。",
            "模型看图通过不等于所有翻译任务都测试过；测试译文只证明该次文字请求返回成功。",
            "翻译视频声音会请求录音权限。内置识别只支持英语；播放 App 可以禁止系统录音，此时改用画面中可见的字幕。",
            "LibreTranslate 是可选项。托管服务可能要求自己的 API Key，自建服务是否需要密钥取决于部署者设置。没有可用服务时，把此项留空，用模型或 NOMI 翻译即可。"
        ), listOf(SetupLink("LibreTranslate API 说明", "https://docs.libretranslate.com/guides/api_usage/"))),
        SetupTopic("备用：阿里云百炼视觉模型", listOf(
            "如果决定使用百炼，先打开官方说明，登录阿里云并按页面提示开通百炼。",
            "在 API Key 页面选择服务地域、账号和业务空间，创建并复制 Key。地域、业务空间、模型和接口地址必须匹配。",
            "在 OpenNomi 自定义连接填写控制台与官方文档显示的 OpenAI 兼容根地址，以及当前可用的视觉模型，例如 qwen3-vl-plus，再做图片测试。",
            "百炼可能按量计费或仅有试用额度，使用前核对账号额度和费用。它是备用服务，不必与智谱一起申请。"
        ), listOf(SetupLink("百炼申请 API Key", "https://help.aliyun.com/zh/model-studio/get-api-key"), SetupLink("百炼视觉接入说明", "https://help.aliyun.com/zh/model-studio/qwen-vl-compatible-with-openai")))
    )
}
