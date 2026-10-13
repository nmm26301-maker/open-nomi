# OpenNomi 0.57.0 完整发布备份

安装包、准确源码提交、原始测试报告、恢复脚本及签名和密码的加密副本均保存在此目录。下载整个目录后运行 `python3 restore.py --output-dir restored`，脚本核对全部分片和完整 APK。

包名 `com.opennomi.android`，版本代码 57，沿用 0.53–0.56 证书，可覆盖升级。新版优先在短停顿处分段，按顺序展示字幕，保留本次共享最近 20 条原文和译文；悬浮菜单可直接打开回看页。首次需准备语音和翻译模型。用法及限制见 [0.57 字幕分段与回看](../../../docs/0.57-subtitle-continuity.md)。

签名加密副本包含 JKS、别名、密码和证书；恢复钥匙单独保管。命令：`python3 signing/decrypt.py --key-file /path/to/OpenNomi-new-signing-recovery-key.json --output-dir restored-signing`。不要提交恢复钥匙或解密输出。验证范围及限制见 `verification.json`。
