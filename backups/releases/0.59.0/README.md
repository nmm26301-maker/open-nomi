# OpenNomi 0.59.0 完整发布备份

安装包、准确源码提交、原始测试报告、恢复脚本及签名和密码的加密副本均保存在此目录。下载整个目录后运行 `python3 restore.py --output-dir restored`，脚本核对全部分片和完整 APK。

包名 `com.opennomi.android`，版本代码 59，沿用 0.53–0.58 证书，可覆盖升级。新版暂停时关闭翻译而不隐式转入画面文字翻译，保留共享与近期译文；回看页可直接继续字幕。常用按钮和译文前移，运行时收起语言与模型设置。用法及限制见 [0.59 暂停恢复与操作区](../../../docs/0.59-video-pause-and-controls.md)。

签名加密副本包含 JKS、别名、密码和证书；恢复钥匙单独保管。命令：`python3 signing/decrypt.py --key-file /path/to/OpenNomi-new-signing-recovery-key.json --output-dir restored-signing`。不要提交恢复钥匙或解密输出。验证范围及限制见 `verification.json`。
