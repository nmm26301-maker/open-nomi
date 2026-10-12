# OpenNomi 0.55.0 完整发布备份

安装包、源码提交、原始验证报告、恢复脚本，以及同一发布签名和密码的加密副本均保存在此目录。

下载整个目录后运行 `python3 restore.py --output-dir restored`。脚本校验每个分片和完整 APK，拒绝覆盖现有输出。

包名 `com.opennomi.android`、版本代码 55；沿用 0.53/0.54 证书，可以覆盖升级。使用原声语言选择和直接声音捕获，为无字幕视频显示中文字幕；首次需准备相应语言模型。具体用法及限制见 [0.55 视频字幕说明](../../../docs/0.55-video-subtitles.md)。

`signing/` 的加密副本包含 JKS、别名和密码，恢复钥匙单独保管。恢复命令：`python3 signing/decrypt.py --key-file /path/to/OpenNomi-new-signing-recovery-key.json --output-dir restored-signing`。

实际测试计数、模拟器验证范围与已知限制见 `verification.json`。
