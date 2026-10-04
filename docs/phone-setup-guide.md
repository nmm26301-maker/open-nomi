# OpenNomi 手机申请与配置指南

这份指南按“没有电脑、尚无模型密钥、Android 13 手机”准备，官方入口核对日期：2026-10-04。

**先做两件事：申请一个智谱密钥；打开手机需要的权限。** 视觉问答、Agent 和模型文字翻译共用该连接。想继续用原声 NOMI，再完成小智设备绑定；AIRI 在自己的页面单独设置。

当前发布安装包仍为 0.40。本次 0.41 增加的申请指南、图片能力测试和独立翻译测试已加入源码，**新 APK 尚未编译，不能在旧安装包里找到这些新增按钮。** 账号申请和已有功能配置可以先完成。

| 功能 | 需要准备什么 | 是否要另开账号 |
|---|---|---|
| 看图、智能看屏幕 | 智谱账号与 API Key，或其他视觉服务 | 需要一个模型服务账号 |
| Agent 规划 | 同一视觉连接；共享与无障碍权限 | 无需单独开户 |
| 模型文字翻译 | 同一模型连接；浮条需要共享权限 | 无需单独开户 |
| 原声 NOMI | 小智账号与当前设备验证码 | 与智谱独立 |
| AIRI | 官方账号或聊天服务配置；声音模块另外设置 | 不自动继承 OpenNomi 设置 |
| OCR、碎片本、记忆、英语视频识别 | 内置数据与相应系统权限 | 无需开户；生成译文仍需要服务 |
| LibreTranslate、百炼 | 可选备用服务 | 暂时可以不申请 |

视觉配置的三个字段：

| 字段 | 内容 |
|---|---|
| 接口地址 | `https://open.bigmodel.cn/api/paas/v4` |
| 模型名称 | `glm-4.6v-flash` |
| API Key | 你在官方平台新建并复制的完整密钥 |

如果手机网页排版不好找按钮，可试浏览器菜单里的“电脑版网页”。平台的登录、验证要求以及费用规则，以当前页面显示为准。

## 先准备哪些东西

1. 视觉问答、Agent 和模型文字翻译共用一个视觉模型连接。先申请智谱 API Key 即可，无需为这三项分别开户。
2. 原声 NOMI 使用小智设备绑定。AIRI 的服务设置独立保存，需要在 AIRI 页面单独配置。
3. 中英文 OCR、碎片本、记忆和英语视频识别数据已内置；它们不需要注册账号。翻译成中文仍需语言模型、NOMI 或翻译服务。


## 智谱视觉模型 · 手机申请

1. 打开智谱官方平台，按页面提示注册或登录。如果平台要求账号验证，按它当前显示的要求完成。
2. 进入 API Keys 管理页，创建一个应用用的密钥，例如命名 OpenNomi，然后复制完整密钥。
3. 回到连接页，选择 GLM 免费视觉模型，将密钥粘贴到 API Key 栏。
4. 点保存并测试看图。识别测试图片成功后，回到看屏幕开启共享，或在碎片本添加图片提问。
5. 地址：https://open.bigmodel.cn/api/paas/v4；模型：glm-4.6v-flash。官方目前列为免费模型，调用仍受账号权限、额度和限流规则约束。密钥只填到手机里。

- [创建智谱 API Key](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)
- [智谱官方申请说明](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction)

## 原声 NOMI · 绑定设备

1. 回到 OpenNomi 首页连接 NOMI。如果显示绑定验证码，先记下本次验证码。
2. 打开小智官方后台并登录，在设备管理或智能体的添加设备入口输入当前验证码。后台页面名称可能随版本变化。
3. 返回 OpenNomi，重新连接，再点开始说话。如果验证码过期，刷新连接获取新的验证码；已经绑定的设备通常无需重新登记。
4. 设备绑定与智谱 API Key 是两套连接，填写视觉密钥不会自动完成原声 NOMI 绑定。

- [小智设备管理](https://xiaozhi.me/console/)

## AIRI · 先让文字对话成功

1. 在 AIRI 页面进入设置 → 服务商 → 聊天。使用自己的兼容服务时选择 OpenAI 兼容 API，填写该服务的密钥和官方 API 根地址。
2. 执行页面提供的 Ping API 或服务验证，然后在设置 → 模块 → 意识/思维选择这个服务和实际可用的模型。先发送一条文字消息检查回复。
3. 如果复用智谱账号，需要在 AIRI 里再次填写密钥与根地址 https://open.bigmodel.cn/api/paas/v4；选择平台当前允许调用的聊天模型。服务商列表能否加载、所用模型是否适配，需要以 AIRI 实测结果为准。
4. 手机听觉使用 Android 系统识别，但 AIRI 仍需聊天服务。若手机没有识别服务，先用文字输入。
5. 角色说话还需要设置言语/语音合成服务和声音；GLM 视觉密钥不会自动提供合成声音。也可以用 AIRI 引导里的官方账号登录选项，权限和费用以该页面为准。

- [AIRI 官方配置说明](https://airi.moeru.ai/docs/zh-Hans/docs/manual/config/providers/consciousness/openai)
- [AIRI 网页使用说明](https://airi.moeru.ai/docs/en/docs/manual/web/)

## 屏幕共享和悬浮球 · 系统权限

1. 看屏幕 → 允许悬浮窗，开启 OpenNomi 的在其他应用上层显示权限。
2. Agent 还需要开启 OpenNomi 无障碍服务；只问图片无需无障碍。无障碍可以读取页面控件并执行你确认的操作。
3. Android 13 若提示受限设置：系统设置 → 应用 → OpenNomi → 右上角更多 → 允许受限制的设置，然后再进入无障碍设置开启服务。
4. 点开启屏幕共享，在系统弹窗同意，然后切到目标 App。通知权限用于更容易找到正在共享的通知和停止入口。每次新共享仍要系统授权。
5. 若小米系统阻止打开外部 App 时显示球球，检查 OpenNomi 的悬浮窗/后台弹出界面权限；具体名称随系统版本不同。锁屏、受保护播放画面和密码输入页可能无法读取。

- [Android 受限设置说明](https://support.google.com/android/answer/12623953?hl=zh-Hans)

## 翻译 · 先测一句，再开浮条

1. 先在连接页测试已保存配置的文字翻译。成功后开启屏幕共享，切到英语或其他非中文页面，再开启实时翻译。
2. 模型看图通过不等于所有翻译任务都测试过；测试译文只证明该次文字请求返回成功。
3. 翻译视频声音会请求录音权限。内置识别只支持英语；播放 App 可以禁止系统录音，此时改用画面中可见的字幕。
4. LibreTranslate 是可选项。托管服务可能要求自己的 API Key，自建服务是否需要密钥取决于部署者设置。没有可用服务时，把此项留空，用模型或 NOMI 翻译即可。

- [LibreTranslate API 说明](https://docs.libretranslate.com/guides/api_usage/)

## 备用：阿里云百炼视觉模型

1. 如果决定使用百炼，先打开官方说明，登录阿里云并按页面提示开通百炼。
2. 在 API Key 页面选择服务地域、账号和业务空间，创建并复制 Key。地域、业务空间、模型和接口地址必须匹配。
3. 在 OpenNomi 自定义连接填写控制台与官方文档显示的 OpenAI 兼容根地址，以及当前可用的视觉模型，例如 qwen3-vl-plus，再做图片测试。
4. 百炼可能按量计费或仅有试用额度，使用前核对账号额度和费用。它是备用服务，不必与智谱一起申请。

- [百炼申请 API Key](https://help.aliyun.com/zh/model-studio/get-api-key)
- [百炼视觉接入说明](https://help.aliyun.com/zh/model-studio/qwen-vl-compatible-with-openai)

## 常见卡点

| 现象 | 下一步 |
|---|---|
| 保存地址后仍不能问图 | 配置保存不代表服务接通，检查密钥和视觉模型名；0.41 会提供真实图片测试 |
| 401 / 密钥无效 | 回官方平台重新复制或生成密钥，在对应服务的密钥栏填写 |
| 403 / 无权调用 | 检查账号验证与该模型权限 |
| 404 / 找不到模型或接口 | 检查根地址、模型名称和地域；AIRI 的根地址不要追加 `/chat/completions` |
| 429 / 限流或额度不足 | 在平台查看额度与限制，稍后重试；不必为了本应用立即购买套餐 |
| NOMI 要求验证码 | 去小智设备管理输入当前验证码，不要把智谱密钥填进去 |
| AIRI 已填服务但不回复 | 到意识/思维模块选择对应服务和模型，并先测试文字对话 |
| Agent 没有控件 | 开启无障碍后回目标页面；某些游戏或画布可能没有可操作控件 |
| 翻译浮条不出现 | 先检查翻译服务，开启共享后回非中文页面；小米系统还需允许悬浮显示 |
| 有字幕但抓不到视频声音 | 播放 App 可能禁止系统录音，切回屏幕字幕翻译 |
| 手机听觉无法开启 | 检查麦克风权限与系统语音识别服务；系统不提供识别时先用文字 |

密钥只填进手机的服务设置，不发到聊天、截图或 GitHub。OpenNomi 工作台和 AIRI 是独立的设置区，即使复用同一个服务账号，也要分别配置。

## 官方资料

- [智谱注册、API Key 与兼容接口说明](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction)
- [GLM-4.6V-Flash 模型说明](https://docs.bigmodel.cn/cn/guide/models/free/glm-4.6v-flash)
- [小智设备登记与常见问题](https://xiaozhi.me/xz-docs/docs/help-doc/xiaozhi-ai-chatbot-q%26a/)
- [AIRI 服务商与模块配置](https://airi.moeru.ai/docs/en/docs/manual/config/common)
- [Android 13 受限设置](https://support.google.com/android/answer/12623953?hl=zh-Hans)
- [Android 共享授权](https://developer.android.com/media/grow/media-projection)
- [Android 播放声音捕获限制](https://developer.android.com/media/platform/av-capture)
- [LibreTranslate 密钥规则](https://docs.libretranslate.com/guides/api_usage/)
- [百炼 API Key 与地域](https://help.aliyun.com/zh/model-studio/get-api-key)
