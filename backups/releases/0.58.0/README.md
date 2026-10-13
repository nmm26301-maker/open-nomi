# OpenNomi 0.58.0 完整发布备份

安装包、准确源码提交、原始测试报告、恢复脚本及签名和密码的加密副本均保存在此目录。下载整个目录后运行 `python3 restore.py --output-dir restored`，脚本核对全部分片和完整 APK。

包名 `com.opennomi.android`，版本代码 58，沿用 0.53–0.57 证书，可覆盖升级。新版增加本机相同短句的成功译文复用、字幕积压数量，以及手动重新同步当前声音。同步跳过未完成语句，保留已完成的近期译文。用法及限制见 [0.58 同步与复用](../../../docs/0.58-subtitle-resync.md)。

签名加密副本包含 JKS、别名、密码和证书；恢复钥匙单独保管。命令：`python3 signing/decrypt.py --key-file /path/to/OpenNomi-new-signing-recovery-key.json --output-dir restored-signing`。不要提交恢复钥匙或解密输出。验证范围及限制见 `verification.json`。
