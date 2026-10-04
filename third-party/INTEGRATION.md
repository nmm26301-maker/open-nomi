# 开源来源与集成记录

本版本新增的本地二进制依赖：

- Tesseract4Android 4.9.0：Apache-2.0。Tesseract API JNI 包通过 AAR 原样编入，未修改其 native 二进制；其 Leptonica、libpng、libjpeg 附属库的许可一并附带。
- tessdata_fast：eng、chi_sim 识别数据，Apache-2.0。通过 LocalOcr 安装到应用私有目录，单工作线程使用一个 TessBaseAPI 实例。
- Vosk 0.3.75：Apache-2.0。PlaybackHearing 直接识别 Android AudioPlaybackCapture 的 16 kHz PCM，不经过麦克风转录。
- vosk-model-small-en-us-0.15：Copyright 2020 Alpha Cephei Inc，Apache-2.0；模型来源与许可见 https://alphacephei.com/vosk/models 。原模型 README 附带。
- JNA 5.18.1：Apache-2.0 / LGPL-2.1 双许可；Android AAR 随包包含 libjnidispatch.so。使用 Apache 许可路线；未修改二进制。

LocalOcr、ScreenShareService、ScreenAssistant、OrbitOverlay、MemoryStore、WorkspaceActivity 与安全动作解析是本项目集成代码。

Open-AutoGLM 通过其公开的 OpenAI 兼容图片消息、do / finish 动作格式接入；StepParser 是 Kotlin 安全数据解析适配，不执行模型返回的 Python 代码。原始参考 client.py：https://github.com/zai-org/Open-AutoGLM/blob/main/phone_agent/model/client.py 。相关 Apache-2.0 许可保留。本版只把模型 Tap 映射到当前已识别的可点击控件，不开放任意 Shell、ADB、未知动作或无人确认的点击。

LibreTranslate 仅通过 HTTP 服务接口连接，并未复制其 AGPL 服务实现。部署服务时应遵循其项目许可。RTranslator、UI-TARS 仅用于调研，没有把其代码或整套模型复制进本包。

旧版 Emotion Ball 许可仍位于 app/src/main/assets/emotion-ball/。其他已有依赖维持 0.39 的 Gradle 声明。

新增 AAR 与 OCR 数据 SHA-256 见 checksums.json。发布 APK 的 ABI 默认是 arm64-v8a。


## 0.45 连续任务和语音协议

采用 78/xiaozhi-esp32 websocket_protocol.cc 中 BinaryProtocol1/2/3 和 OTA websocket.version 选择流程；本项目以 Kotlin 重新实现并校验长度。MIT 许可保存到 APK open-source-notices/xiaozhi-esp32-LICENSE。

HandsFreeTasks 根据 Open-AutoGLM phone_agent/agent.py 的 observe/plan/execute loop 适配 Android 无障碍，保留 Apache-2.0 许可；实际循环现在能自动执行普通步骤、重新观察、停止无效循环，并等待敏感动作的语音确认。

调研 xiaoniu/xiaozhi-ai-android 的 WebSocketManager 文字 source=text 与 Opus 播放，未复制其源码和完整 App。
