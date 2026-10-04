# OpenNomi Android 0.41

0.41 加入手机视觉接入、真实图片能力测试和 Agent / 翻译无响应修复。**云端编译与测试状态见本仓库 Actions；正式安装包需要原签名。** 见 [0.41 说明与验证边界](docs/0.41-vision-connection.md) 和 [手机申请与配置指南](docs/phone-setup-guide.md)。此前 0.40 已发布功能见 [屏幕工作台说明](docs/0.40-screen-workspace.md)。

原声 NOMI 与 AIRI 保留；英语视频识别、中英文 OCR 数据随包附带。智能看图与 Agent 规划需要视觉模型服务。NOMI 已连接时也可用于文字问答和翻译。

默认发布 ARM64 版本，适用于 Mi 10 Pro 等 64 位 Android 手机。其他 ABI 可通过 `-POPENNOMI_ABIS=arm64-v8a,armeabi-v7a,x86,x86_64` 构建。

# 0.39 基础行为

只保留原来的两个入口：NOMI 原声在线对话、AIRI 网页角色。

- 继续使用 0.29 的 Emotion Ball 形象，球球区域从固定 260dp 改为随屏幕宽度和可用高度调整。
- 深色一体背景、弹性滑动页签、状态淡入、语音电平与按钮按压反馈。
- 表情 SVG 绘制目标约 30fps；进入后台停止表情循环；设置中可减少动效。
- 删除本地模型、五角色、模型导入、角色网关和第三个页签。升级时清理应用私有 offline-models 目录；不删除用户下载目录中的文件。
- 保留安装包 ID、设备标识、绑定信息与 AIRI 登录。首次进入预连接语音服务；麦克风在用户主动开始对话后才开启。
- 手动模式保留 700ms 说完等待；0.39 将起播预缓冲由 180ms 调整为 120ms。
- 收到 TTS 结束后，按顺序等待音频队列和音轨播放结束，取消额外固定 150ms 等待；连续对话恢复录音的额外等待由 200ms 调整为 120ms。
- 音频解码复用缓冲，重连复用网络线程池，呼吸渐变复用绘制资源，减少循环分配。
- 连接中可取消已排队的开始说话；滑动球球不再触发点击；连续对话衔接避免短暂闪回暂停状态。
- 原有设备绑定及签名保持兼容。网络和服务端响应不受本地参数保证，实际体验需真机验证。

## 构建

需要 JDK 17、Gradle 8.7、Android SDK 35 / Build Tools 35.0.0。

```sh
python3 scripts/restore-build-assets.py
gradle :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
node --test airi-native-voice.test.cjs
```

发布签名由外部 Gradle 属性 OPENNOMI_KEYSTORE、OPENNOMI_STORE_PASSWORD、OPENNOMI_KEY_ALIAS、OPENNOMI_KEY_PASSWORD 提供，源码不含密钥。

## 动效参考与素材

见 docs/motion-sources.md。原有球球许可与使用声明完整保留在 app/src/main/assets/emotion-ball/。

## 手机查看编译

打开 [Actions](https://github.com/nmm26301-maker/open-nomi/actions)，查看 OpenNomi Android checks。每次 main 更新自动编译，也可以 Run workflow 手动运行。产物含未签名 APK 和检查报告；未签名产物不能直接安装。OCR、英语识别和 native AAR 在构建时从官方来源恢复，逐文件核对 SHA-256，安装包仍包含离线数据。
