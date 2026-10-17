# OpenNomi 0.61.0 完整发布备份

包名 com.opennomi.android，版本代码 61，沿用 0.53–0.59 发布证书，可覆盖升级这些版本。
本版改进长字幕阅读时间、将原生录音停止移出界面线程，并在文字翻译完成后自动收起键盘、显示真实译文。见 ../../../docs/0.61-visible-translation-result.md。

完整 APK 由 apk-parts 备份；下载整个目录后运行 python3 restore.py --output-dir restored。
脚本核验每个分片、完整文件大小与 SHA-256。精确构建源码提交和测试原始报告见 manifest.json、verification.json 与 validation/。

signing/ 的 AES-GCM 备份包含 JKS、别名、库密码、密钥密码和证书；恢复钥匙单独保存，禁止提交。
恢复方法：python3 signing/decrypt.py --key-file /path/to/OpenNomi-new-signing-recovery-key.json --output-dir restored-signing。
