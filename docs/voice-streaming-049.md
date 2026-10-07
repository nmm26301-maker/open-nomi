# OpenNomi 0.49 实时语音

安装与 0.48 使用相同包名和签名的正式 APK，可覆盖升级，保留账户与设备身份。

## 更早开始回复

FishAudio 默认打开“边收边播”。小智回答到达一个完整句子时即发起合成，HTTP 音频到达就通过 Android AudioTrack 播放，无需等整个回答或整个音频文件结束。请求使用官方 PCM、24kHz、16 位单声道、balanced 延迟、chunk_length=100。句子按顺序播放，不并发朗读。

首页设置打开“快速回复”后，轮流对话的本地停顿判定由 700ms 缩短为 480ms。句中停顿容易被截断时可以关闭。全双工模式的服务端断句仍由所选服务器控制。小智原声的本地启动缓冲由 120ms 减到 60ms。

播放状态显示本次合成请求到音轨启动的首段耗时。这个数字不包含前面的识别和模型思考时间，也不等于端到端对话速度。

## 边听边说与打断

首页选“聊天”，在设置打开“允许打断”，并在 FishAudio 设置打开“边收边播”。Android 回声消除实际启用成功时，录音在 FishAudio 播放期间继续，开口会取消当前合成下载、释放播放音轨、清空剩余句子，并继续接受新的识别结果。没有可用回声消除时自动改为轮流说话。双工使用通话音量，独立朗读使用媒体音量。

这里实现的是客户端同时录音播放与语音打断。真实电话效果、回声抑制和服务端对话速度，需要在 Mi 10 Pro 与实际账户上测试；它不会把现有小智模型自动变成原生音频全双工模型。点球球仍可暂停。

兼容 FishAudio 服务如未支持原始 PCM，可在 FishAudio 设置关闭“边收边播”，使用 MP3 播放。原有独立朗读、内部申请账户、任务控制和系统朗读设置保留。

## 备用接口

首页设置 → “备用语音接口”：启用后填写自己的小智兼容 WebSocket URL、Token、协议版本并保存重连。关闭后回到原小智连接，备用配置保留。Token 使用 Android Keystore 加密保存。公网地址使用 wss://，局域网可使用 ws://。常见开源小智后端使用协议 1，但必须与服务端配置一致。

支持的是小智语音协议（hello、STT、LLM、TTS、Opus 帧），普通 /chat/completions 或 /v1/realtime 地址不能直接填在此处。对话模型、流式 ASR、TTS 在所连接的服务器后台设置；没有偷偷选用公共测试服务器或新增收费账户。

## 查到的开源方案

| 项目 | 作用 | 本次选择 |
| --- | --- | --- |
| [xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server) | MIT，小智协议后端，支持多种模型和流式 ASR/TTS | 新增兼容备用接口，可连接自己部署的服务；未代部署 |
| [Pipecat](https://github.com/pipecat-ai/pipecat) | 实时语音 Agent 框架，连接识别、模型、合成和传输 | 作为进一步搭建实时服务器的候选，未嵌入 Android |
| [vLLM-Omni duplex API](https://github.com/vllm-project/vllm-omni/blob/main/docs/serving/realtime_duplex_api.md) | 原生全双工模型服务和专用会话、打断、播放进度协议 | 需要 duplex 服务与客户端适配，当前备用小智接口不支持该协议 |
| [Fish Speech](https://github.com/fishaudio/fish-speech) | 可查看与部署的 TTS 源码、模型；当前采用 Fish Audio Research License | 仅作为自托管合成候选；未打包模型到手机，不能按 MIT 许可理解 |

协议依据：[FishAudio REST TTS](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech)、[流式生成说明](https://docs.fish.audio/developer-guide/best-practices/real-time-streaming)。官方支持 PCM 原始样本，适合边收边播。免费模型的真实首段时间仍受账户、网络与服务负载影响。
