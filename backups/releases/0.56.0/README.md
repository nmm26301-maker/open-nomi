# OpenNomi 0.56.0 完整发布备份

安装包、准确源码提交、原始测试报告、恢复脚本及签名和密码的加密副本均保存在此目录。下载整个目录后运行 `python3 restore.py --output-dir restored`，脚本核对全部分片和完整 APK。

包名 `com.opennomi.android`，版本代码 56，沿用 0.53–0.55 证书，可覆盖升级。新版提供视频字幕、文字翻译、画面文字三个入口，以及更快显示／完整语句选择；首次需准备语音和翻译模型。用法及限制见 [0.56 视频字幕优化](../../../docs/0.56-video-optimization.md)。

签名加密副本包含 JKS、别名、密码和证书；恢复钥匙单独保管。命令：`python3 signing/decrypt.py --key-file /path/to/OpenNomi-new-signing-recovery-key.json --output-dir restored-signing`。不要提交恢复钥匙或解密输出。验证范围及限制见 `verification.json`。
