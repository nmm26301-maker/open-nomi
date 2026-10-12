# OpenNomi Android 0.54.0

本次更新重点是连续语音体验：过滤空白识别事件与同一回答期间重复发送的最终识别结果，新增三档说完等待，并将 FishAudio 快速分句播放与麦克风停顿时间分开设置。详情见 [0.53 语音体验与安装说明](docs/0.53-voice-experience.md)。

本次补上无需密钥的手机翻译和直接试译界面，修复未配置视觉服务就无法开启翻译的阻断；继续修复取消开始说话后的重连恢复，并让 FishAudio 在较长短句的逗号到达时立即启动合成，避免等待后续文字。见 [0.54 更新与备份说明](docs/0.54-voice-and-backup.md)。

## 安装身份与签名

0.54.0 沿用 0.53.0 的包名和新发布签名，可覆盖升级 0.53.0。新版包名为 `com.opennomi.android`，版本代码 54，使用用户保留的新发布签名。它与旧版 `com.opennomi.android.voicetest` 并行安装，不会覆盖或清除旧版。新安装需重新授予权限、绑定设备、填写服务配置；旧版的本机聊天记忆、登录和设置不会自动迁移。不要为了安装新版卸载旧版。

签名私钥和密码只在外部签名步骤使用，源码与 Actions 日志不包含这些凭据。加密备份见本仓库 `backups/signing/new-signing-20261012/`，每版安装包和恢复校验文件归档于 `backups/releases/<版本>/`；恢复凭据单独保存，禁止提交仓库。没有原私钥时不能生成旧包名的覆盖升级包。

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

打开 [Actions](https://github.com/nmm26301-maker/open-nomi/actions)，查看 OpenNomi Android checks。每次 main 更新自动编译，也可以 Run workflow 手动运行。产物含未签名 APK 的三个分片、签名工具和检查报告；未签名产物不能直接安装。下载并解压三个 apk-part 产物后，按 part00、part01、part02 顺序拼接为 APK，核对 unsigned-sha256.txt，再用原签名签名。OCR、英语识别和 native AAR 在构建时从官方来源恢复，逐文件核对 SHA-256，安装包仍包含离线数据。

构建记录见仓库 Actions；旧版本验证记录仅适用于对应版本。API Key 在手机里填写；屏幕共享、Agent 与实时翻译的完整体验仍需 Android 13 真机测试。

## 完整发布备份

0.53.0 的已签名 APK、逐片与完整校验清单、恢复脚本，以及本版所需签名和密码的加密副本已归档到 [backups/releases/0.53.0](backups/releases/0.53.0/)。统一签名备份在 [backups/signing/new-signing-20261012](backups/signing/new-signing-20261012/)。解密钥匙单独保存，不提交仓库。
